package com.typefree.ime.v391

import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.engine.RimeInputEngine
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class V391RimeMirrorAndT9ResilienceTest {

    @Test
    fun `resolveDownloadMirrorChain prioritizes CN direct, jsDelivr CDN, and gh-proxy before GitHub origin`() {
        val rimeIceChain = RimeDataManager.resolveDownloadMirrorChain(RimeDataManager.RIME_ICE_BASE_DICT_URL)
        assertEquals(
            "https://typefree.xuezhao.space/downloads/rime/base.dict.yaml",
            rimeIceChain.first()
        )
        assertTrue(
            rimeIceChain.any {
                it == "https://fastly.jsdelivr.net/gh/iDvel/rime-ice@${RimeDataManager.RIME_ICE_RELEASE_COMMIT}/cn_dicts/base.dict.yaml"
            }
        )
        assertTrue(
            rimeIceChain.any {
                it == "https://testingcf.jsdelivr.net/gh/iDvel/rime-ice@${RimeDataManager.RIME_ICE_RELEASE_COMMIT}/cn_dicts/base.dict.yaml"
            }
        )
        assertTrue(rimeIceChain.any { it.startsWith("https://gh-proxy.com/") })
        assertEquals(RimeDataManager.RIME_ICE_BASE_DICT_URL, rimeIceChain.last())

        val gramUrl = RimeDataManager.GRAMMAR_MODEL_PRESETS.first().url
        val gramChain = RimeDataManager.resolveDownloadMirrorChain(gramUrl)
        assertEquals(
            "https://typefree.xuezhao.space/downloads/rime/zh-hans-t-essay-bgc-compact.gram",
            gramChain.first()
        )
        assertTrue(gramChain.any { it == "https://gh-proxy.com/$gramUrl" })
        assertTrue(gramChain.any { it == "https://hk.gh-proxy.com/$gramUrl" })
        assertEquals(gramUrl, gramChain.last())
    }

    @Test
    fun `failed dictionary install preserves RimeDataManager isReady and T9 Pinyin typing remains functional`() {
        val tempRoot = Files.createTempDirectory("v391_t9_resilience").toFile()
        try {
            val fakeBridge = FakeT9CapableRimeSession()
            val dm = RimeDataManager(
                sharedDir = java.io.File(tempRoot, "shared"),
                userDir = java.io.File(tempRoot, "user"),
                rimeBridge = fakeBridge
            )

            // 1. Cold start deploys built-in dictionary cleanly
            val initial = dm.deployDefaultSync(InputScheme.RIME_T9_PINYIN)
            assertTrue(initial is RimeDeploymentState.Ready)
            assertTrue(dm.isReady)

            // 2. Simulate a failed online dictionary download / corrupted stream
            var threw = false
            try {
                dm.installFullRimeIceFromStreamSync(
                    inputStream = ByteArrayInputStream("corrupted partial network payload".toByteArray()),
                    expectedSha256 = RimeDataManager.RIME_ICE_BASE_DICT_SHA256
                )
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue("Corrupted download must throw SecurityException", threw)
            assertTrue(
                "UI deploymentState reflects Error for toast/card feedback",
                dm.deploymentState.value is RimeDeploymentState.Error
            )
            assertTrue(
                "Built-in Rime engine MUST stay ready (isReady == true) even when download fails",
                dm.isReady
            )

            // 3. Verify T9 Pinyin engine auto-aligns schema to "t9" and continues producing candidates
            val engine = RimeInputEngine(
                rimeBridge = fakeBridge,
                initialScheme = InputScheme.RIME_T9_PINYIN
            )
            // Even if activeSchemaId drifted to rime_ice before T9 input, onCharInput self-heals to "t9"
            fakeBridge.forceActiveSchema("rime_ice")
            val res1 = engine.onKey(KeyInput.CharKey('6'))
            val res2 = engine.onKey(KeyInput.CharKey('4'))
            assertTrue(res1 is EngineResult.Consumed)
            assertTrue(res2 is EngineResult.Consumed)
            assertEquals("t9", fakeBridge.activeSchemaId)
            assertTrue(engine.state.value.isComposing)
            assertEquals("你好", engine.state.value.candidates.first().text)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `failed download before initial deploy finishes still self-heals built-in engine`() {
        val tempRoot = Files.createTempDirectory("v391_cold_fail_heal").toFile()
        try {
            val fakeBridge = FakeT9CapableRimeSession()
            val dm = RimeDataManager(
                sharedDir = java.io.File(tempRoot, "shared"),
                userDir = java.io.File(tempRoot, "user"),
                rimeBridge = fakeBridge
            )

            // Trigger failed grammar install BEFORE deployDefaultSync was ever called
            runCatching {
                dm.installGrammarModelFromStreamSync(
                    modelId = "zh-hans-t-essay-bgc-compact",
                    inputStream = ByteArrayInputStream(ByteArray(0)),
                    expectedBytes = 1024L
                )
            }
            assertTrue(dm.deploymentState.value is RimeDeploymentState.Error)
            assertTrue(
                "ensureBuiltInFallbackEngineOperational must bring built-in Rime online",
                dm.isReady
            )
            assertTrue(fakeBridge.isDeployed)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private class FakeT9CapableRimeSession : RimeSessionClient {
        override var activeSchemaId: String = "rime_ice"
            private set
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = true
        override var isDeployed: Boolean = false
            private set
        override val wordCount: Int = 8105
        private val rawBuf = StringBuilder()

        fun forceActiveSchema(id: String) {
            activeSchemaId = id
        }

        override fun initialize(sharedDir: String, userDir: String): Boolean = true

        override fun deploy(schemaId: String): Boolean {
            if (schemaId.isNotBlank()) activeSchemaId = schemaId
            isDeployed = true
            return true
        }

        override fun selectSchema(schemaId: String): Boolean {
            if (!isDeployed) return false
            activeSchemaId = schemaId
            return true
        }

        override fun processKey(keyCode: Int, mask: Int): Boolean {
            rawBuf.append(keyCode.toChar())
            return true
        }

        override fun processKeyText(keys: String): RimeContextSnapshot {
            rawBuf.clear().append(keys)
            return getContext(pageSize)
        }

        override fun getContext(maxCandidates: Int): RimeContextSnapshot {
            val raw = rawBuf.toString()
            if (raw.isEmpty()) return RimeContextSnapshot()
            val cands = if (activeSchemaId == "t9" && raw == "64") {
                listOf(RimeCandidate("你好"), RimeCandidate("你"))
            } else {
                listOf(RimeCandidate(raw))
            }
            return RimeContextSnapshot(
                preedit = if (raw == "64") "ni" else raw,
                rawInput = raw,
                candidates = cands
            )
        }

        override fun selectCandidate(index: Int): String? {
            val picked = getContext(pageSize).candidates.getOrNull(index)?.text
            rawBuf.clear()
            return picked
        }

        override fun commitComposition(): String? {
            val first = getContext(pageSize).candidates.firstOrNull()?.text
            rawBuf.clear()
            return first
        }

        override fun getCommit(): String? = null
        override fun clearComposition() {
            rawBuf.clear()
        }
        override fun setOption(option: String, value: Boolean) {}
        override fun getOption(option: String): Boolean = false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-fake"
    }
}
