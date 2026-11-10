package com.typefree.ime.v27

import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.FlypyInputEngine
import com.typefree.ime.ime.engine.InputEngine
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.engine.RimeInputEngine
import com.typefree.ime.ime.flypy.FlypyDictionary
import com.typefree.ime.ime.flypy.FlypyEngine
import com.typefree.rime.RimeBridge
import com.typefree.rime.RimeCandidate
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InputEngineContractAndRimeTest {

    companion object {
        private lateinit var sharedFlypyDict: FlypyDictionary

        @JvmStatic
        @BeforeClass
        fun loadFlypyAssetsOnce() {
            val dictFile = listOf(
                File("src/main/assets/flypy_dict.tsv"),
                File("app/src/main/assets/flypy_dict.tsv")
            ).firstOrNull { it.exists() }
            val dzFile = listOf(
                File("src/main/assets/flypy_dz.tsv"),
                File("app/src/main/assets/flypy_dz.tsv")
            ).firstOrNull { it.exists() }

            assertNotNull("Expected flypy_dict.tsv in assets", dictFile)
            assertNotNull("Expected flypy_dz.tsv in assets", dzFile)

            FileInputStream(dictFile!!).use { dictIn ->
                FileInputStream(dzFile!!).use { dzIn ->
                    sharedFlypyDict = FlypyDictionary.loadFromStreams(dictIn, dzIn)
                }
            }
            assertTrue(sharedFlypyDict.isLoaded)
        }
    }

    /**
     * Lightweight test fake implementing [RimeSessionClient] strictly inside `src/test`
     * to isolate [RimeInputEngine] and [RimeDataManager] contract tests on the JVM
     * while production [RimeBridge] remains a pure thin JNI wrapper over `librime`.
     */
    private class FakeRimeSessionClient : RimeSessionClient {
        override var activeSchemaId: String = "rime_ice"
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = false
        override var wordCount: Int = 0

        val options = mutableMapOf<String, Boolean>()
        val userBoostedWords = mutableMapOf<String, MutableSet<String>>()
        private val rawBuffer = StringBuilder()
        private var lastCommit: String? = null
        var sharedDirPath: String = ""
        var userDirPath: String = ""

        private val pinyinFixtures = mapOf(
            "n" to ("n" to listOf("你", "呢", "那")),
            "ni" to ("ni" to listOf("你", "泥", "拟")),
            "nih" to ("ni'h" to listOf("你好", "你", "泥")),
            "niha" to ("ni'ha" to listOf("你好", "你哈")),
            "nihao" to ("ni'hao" to listOf("你好", "拟好", "你号")),
            "nihaoshijie" to ("ni'hao'shi'jie" to listOf("你好世界", "你好", "你")),
            "women" to ("wo'men" to listOf("我们", "我门", "我")),
            "jintiantianqi" to ("jin'tian'tian'qi" to listOf("今天天气", "今天", "今")),
            "shijian" to ("shi'jian" to listOf("时间", "实践", "事件")),
            "fanzhongliyinqing" to ("fan'zhong'li'yin'qing" to listOf("反重力引擎", "反重力"))
        )

        private val doublePinyinFixtures = mapOf(
            "nihc" to ("ni'hao" to listOf("你好", "拟好")),
            "uijm" to ("shi'jian" to listOf("时间", "实践", "事件")),
            "vsgo" to ("zhong'guo" to listOf("中国", "忠告"))
        )

        override fun initialize(sharedDir: String, userDir: String): Boolean {
            sharedDirPath = sharedDir
            userDirPath = userDir
            return true
        }

        override fun deploy(schemaId: String): Boolean {
            if (schemaId.isNotBlank()) activeSchemaId = schemaId
            isDeployed = true
            val baseDict = File(sharedDirPath, RimeDataManager.BASE_DICT_RELATIVE_PATH)
            val charDict = File(sharedDirPath, RimeDataManager.CHAR_DICT_RELATIVE_PATH)
            wordCount = (if (baseDict.exists()) 18009 else 0) + (if (charDict.exists()) 8105 else 0)
            return true
        }

        override fun selectSchema(schemaId: String): Boolean {
            activeSchemaId = schemaId
            rawBuffer.clear()
            return true
        }

        override fun processKey(keyCode: Int, mask: Int): Boolean {
            lastCommit = null
            if (keyCode == RimeBridge.XK_BackSpace) {
                if (rawBuffer.isNotEmpty()) {
                    rawBuffer.deleteCharAt(rawBuffer.length - 1)
                    return true
                }
                return false
            }
            val ch = keyCode.toChar()
            if (ch in 'a'..'z' || ch == '\'') {
                rawBuffer.append(ch)
                return true
            }
            return false
        }

        override fun processKeyText(keys: String): RimeContextSnapshot {
            rawBuffer.clear()
            keys.forEach { processKey(it.code, 0) }
            return getContext(pageSize)
        }

        override fun getContext(maxCandidates: Int): RimeContextSnapshot {
            val raw = rawBuffer.toString()
            if (raw.isEmpty()) return RimeContextSnapshot()
            val table = if (activeSchemaId == "double_pinyin_flypy") doublePinyinFixtures else pinyinFixtures
            val (preedit, baseList) = table[raw] ?: (raw to listOf("候选$raw", "次选$raw", "三选$raw"))
            val fixedOrder = options["fixed_candidate_order"] == true
            val boosted = if (!fixedOrder) userBoostedWords[raw].orEmpty() else emptySet()
            val ordered = if (boosted.isEmpty()) {
                baseList
            } else {
                boosted.toList() + baseList.filterNot { it in boosted }
            }
            val cands = ordered.take(maxCandidates).map { RimeCandidate(it) }
            return RimeContextSnapshot(
                preedit = preedit,
                rawInput = raw,
                candidates = cands,
                hasMore = ordered.size > maxCandidates
            )
        }

        override fun selectCandidate(index: Int): String? {
            val snap = getContext(pageSize)
            val picked = snap.candidates.getOrNull(index)?.text ?: return null
            val raw = rawBuffer.toString()
            val incognito = options["_incognito"] == true || options["enable_user_dict"] == false
            if (!incognito && raw.isNotEmpty()) {
                userBoostedWords.getOrPut(raw) { linkedSetOf() }.add(picked)
            }
            rawBuffer.clear()
            lastCommit = picked
            return picked
        }

        override fun commitComposition(): String? {
            return selectCandidate(0)
        }

        override fun getCommit(): String? {
            val c = lastCommit
            lastCommit = null
            return c
        }

        override fun clearComposition() {
            rawBuffer.clear()
            lastCommit = null
        }

        override fun setOption(option: String, value: Boolean) {
            options[option] = value
        }

        override fun getOption(option: String): Boolean = options[option] ?: false

        override fun syncUserData(): Boolean = true

        override fun getVersion(): String = "1.17.0-fake-test"
    }

    private fun createTempRimeSetup(
        scheme: InputScheme = InputScheme.RIME_PINYIN
    ): Triple<File, RimeDataManager, RimeInputEngine> {
        val tempRoot = Files.createTempDirectory("typefree_rime_test_").toFile()
        val sharedDir = File(tempRoot, "rime/shared")
        val userDir = File(tempRoot, "rime/user")
        val fakeBridge = FakeRimeSessionClient()
        val manager = RimeDataManager(
            sharedDir = sharedDir,
            userDir = userDir,
            rimeBridge = fakeBridge,
            appContext = null
        )
        val deployState = manager.deployDefaultSync(scheme)
        assertTrue(
            "Expected Rime default deployment to succeed, got $deployState",
            deployState is RimeDeploymentState.Ready
        )
        val engine = RimeInputEngine(rimeBridge = fakeBridge, initialScheme = scheme)
        return Triple(tempRoot, manager, engine)
    }

    private fun typeChars(engine: InputEngine, text: String, nowMillis: Long = 1_700_000_000_000L): EngineResult {
        var lastResult: EngineResult = EngineResult.Consumed
        for (ch in text) {
            lastResult = engine.onKey(KeyInput.CharKey(ch, nowMillis))
        }
        return lastResult
    }

    // =========================================================================
    // 1. Unified InputEngine Contract Suite (FlypyInputEngine + RimeInputEngine)
    // =========================================================================

    @Test
    fun inputEngineContract_bothFlypyAndRimeHonorCoreKeystrokeSemantics() {
        val (tempRoot, _, rimeEngine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            val flypyEngine = FlypyInputEngine(FlypyEngine(sharedFlypyDict))
            val engines: List<Pair<String, InputEngine>> = listOf(
                "FlypyInputEngine" to flypyEngine,
                "RimeInputEngine" to rimeEngine
            )

            for ((label, engine) in engines) {
                // 1) Composing + Clear Code (`有码清码`)
                engine.clear()
                assertFalse("$label should start idle", engine.state.value.isComposing)
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey('n')))
                assertTrue("$label should be composing after char input", engine.state.value.isComposing)
                engine.clear()
                assertFalse("$label should clear composing on clear()", engine.state.value.isComposing)
                assertEquals("", engine.state.value.rawCode)

                // 2) Space commits 1st candidate (`空格首选上屏`)
                val sampleCode = if (engine is FlypyInputEngine) "nh" else "nihao"
                typeChars(engine, sampleCode)
                assertTrue("$label should have candidates for $sampleCode", engine.state.value.candidates.isNotEmpty())
                val expectedFirst = engine.state.value.candidates.first().text
                val spaceRes = engine.onKey(KeyInput.Space)
                assertEquals("$label space should commit 1st candidate", EngineResult.Commit(expectedFirst, false), spaceRes)
                assertFalse("$label should finish composing after space", engine.state.value.isComposing)

                // 3) Punctuation top-up (`标点顶屏` when commaPeriodQuickSelect = false)
                typeChars(engine, sampleCode)
                val firstBeforePunct = engine.state.value.candidates.first().text
                val punctTopUpRes = engine.onKey(
                    KeyInput.CommaOrPeriod(isComma = true, commaPeriodQuickSelect = false)
                )
                assertEquals(
                    "$label comma with quickSelect=false should top-up 1st candidate + ，",
                    EngineResult.Commit(firstBeforePunct + "，", false),
                    punctTopUpRes
                )
                assertFalse(engine.state.value.isComposing)

                // 4) Comma secondary quick-select (`,` selects 2nd candidate when commaPeriodQuickSelect = true)
                typeChars(engine, sampleCode)
                assertTrue("$label should have >=2 candidates for $sampleCode", engine.state.value.candidates.size >= 2)
                val expectedSecond = engine.state.value.candidates[1].text
                val commaSelectRes = engine.onKey(
                    KeyInput.CommaOrPeriod(isComma = true, commaPeriodQuickSelect = true)
                )
                assertEquals(
                    "$label comma with quickSelect=true should commit 2nd candidate",
                    EngineResult.Commit(expectedSecond, false),
                    commaSelectRes
                )

                // 5) Enter commits raw ASCII code (`回车上屏原始编码`)
                typeChars(engine, "ab")
                assertEquals("ab", engine.state.value.rawCode)
                val enterRes = engine.onKey(KeyInput.Enter)
                assertEquals(
                    "$label Enter while composing should commit raw ASCII code",
                    EngineResult.Commit("ab", false),
                    enterRes
                )
                assertFalse(engine.state.value.isComposing)

                // 6) `;q` & `;w` Quick Symbols (`;q -> ：“`, `;w -> ？`) and `;f` repeat commit
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey(';')))
                assertEquals(EngineResult.Commit("：“", false), engine.onKey(KeyInput.CharKey('q')))
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey(';')))
                assertEquals(EngineResult.Commit("？", false), engine.onKey(KeyInput.CharKey('w')))
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey(';')))
                assertEquals(EngineResult.Commit("？", false), engine.onKey(KeyInput.CharKey('f')))

                // 7) `orq`, `ouj`, `oxq` dynamic date/time/weekday commands via shared CandidateMerger
                val fixedTimeMs = 1_715_000_000_000L
                for (cmd in listOf("orq", "ouj", "oxq")) {
                    engine.clear()
                    typeChars(engine, cmd, nowMillis = fixedTimeMs)
                    val cands = engine.state.value.candidates
                    assertTrue("$label should produce dynamic candidates for $cmd", cands.size >= 2)
                    if (cmd == "orq") {
                        assertTrue("$label orq should contain 年/月/日", cands[0].text.contains("年"))
                    } else if (cmd == "ouj") {
                        assertTrue("$label ouj should contain :", cands[0].text.contains(":"))
                    } else if (cmd == "oxq") {
                        assertTrue("$label oxq should start with 星期", cands[0].text.startsWith("星期"))
                    }
                    engine.clear()
                }

                // 8) Custom snippet expansion (`;sd` & `;yx`)
                val snippets = listOf(
                    SnippetEntry(
                        title = "邮箱",
                        voiceTriggers = "我的邮箱",
                        flypyCode = ";yx",
                        content = "dev@typefree.ai",
                        enabled = true
                    ),
                    SnippetEntry(
                        title = "地址",
                        voiceTriggers = "收货地址",
                        flypyCode = ";sd",
                        content = "上海市浦东新区张江高科",
                        enabled = true
                    )
                )
                engine.updateSnippets(snippets)
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey(';')))
                assertEquals(EngineResult.Consumed, engine.onKey(KeyInput.CharKey('y')))
                val snipRes = engine.onKey(KeyInput.CharKey('x'))
                assertEquals(
                    "$label should auto-commit unique ;yx snippet",
                    EngineResult.Commit("dev@typefree.ai", false),
                    snipRes
                )
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. Rime Full Pinyin (`rime_ice`) & Xiaohe Double-Pinyin (`double_pinyin_flypy`)
    // =========================================================================

    @Test
    fun rimePinyinAndDoublePinyin_resolveSegmentedPreeditAndSentenceCandidates() {
        val (tempRoot, _, engine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            // 1) Full Pinyin `nihao` -> `你好` with preedit `ni'hao`
            typeChars(engine, "nihao")
            assertEquals("ni'hao", engine.state.value.preedit)
            assertEquals("你好", engine.state.value.candidates.first().text)
            assertEquals(EngineResult.Commit("你好", false), engine.onKey(KeyInput.Space))

            // 2) Full Pinyin `women` & `jintiantianqi`
            typeChars(engine, "women")
            assertEquals("wo'men", engine.state.value.preedit)
            assertEquals("我们", engine.state.value.candidates.first().text)
            engine.clear()

            typeChars(engine, "jintiantianqi")
            assertEquals("jin'tian'tian'qi", engine.state.value.preedit)
            assertEquals("今天天气", engine.state.value.candidates.first().text)
            engine.clear()

            // 3) Switch to Xiaohe Double-Pinyin (`RIME_DOUBLE_PINYIN_FLYPY`)
            engine.setInputScheme(InputScheme.RIME_DOUBLE_PINYIN_FLYPY)
            typeChars(engine, "nihc")
            assertEquals("ni'hao", engine.state.value.preedit)
            assertEquals("你好", engine.state.value.candidates.first().text)
            assertEquals(EngineResult.Commit("你好", false), engine.onKey(KeyInput.Space))

            // 4) Xiaohe Double-Pinyin `uijm` (`shi'jian`) -> `时间`, `实践`, `事件`
            typeChars(engine, "uijm")
            assertEquals("shi'jian", engine.state.value.preedit)
            val uijmTexts = engine.state.value.candidates.map { it.text }
            assertTrue("Expected 时间 and 实践 in $uijmTexts", "时间" in uijmTexts && "实践" in uijmTexts)
            engine.clear()

            // 5) Xiaohe Double-Pinyin `vsgo` (`zhong'guo`) -> `中国`
            typeChars(engine, "vsgo")
            assertEquals("zhong'guo", engine.state.value.preedit)
            assertEquals("中国", engine.state.value.candidates.first().text)
            engine.clear()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // 3. Rime Native Options (`_incognito`, `enable_user_dict`, `fixed_candidate_order`)
    // =========================================================================

    @Test
    fun rimeInputEngine_propagatesIncognitoAndFixedCandidateOrderOptionsToLibrimeSession() {
        val (tempRoot, _, engine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            // 1) Incognito mode (`setIncognitoMode(true)`): sets `_incognito=true` and `enable_user_dict=false`
            engine.setIncognitoMode(true)
            assertTrue(engine.rimeBridge.getOption("_incognito"))
            assertFalse(engine.rimeBridge.getOption("enable_user_dict"))

            typeChars(engine, "shijian")
            val shijianIdx = engine.state.value.candidates.indexOfFirst { it.text == "实践" }
            assertTrue(shijianIdx >= 1)
            assertEquals("时间", engine.state.value.candidates.first().text)

            // Selecting "实践" in incognito mode does NOT boost its frequency
            assertEquals(EngineResult.Commit("实践", false), engine.select(shijianIdx))
            typeChars(engine, "shijian")
            assertEquals("时间", engine.state.value.candidates.first().text)
            engine.clear()

            // 2) Turn off incognito mode -> selecting "实践" boosts it to #1
            engine.setIncognitoMode(false)
            assertFalse(engine.rimeBridge.getOption("_incognito"))
            assertTrue(engine.rimeBridge.getOption("enable_user_dict"))

            typeChars(engine, "shijian")
            val idxToBoost = engine.state.value.candidates.indexOfFirst { it.text == "实践" }
            assertEquals(EngineResult.Commit("实践", false), engine.select(idxToBoost))

            typeChars(engine, "shijian")
            assertEquals("实践", engine.state.value.candidates.first().text)
            engine.clear()

            // 3) Fixed Candidate Order (`setFixedCandidateOrder(true)`): sets `fixed_candidate_order=true`
            engine.setFixedCandidateOrder(true)
            assertTrue(engine.rimeBridge.getOption("fixed_candidate_order"))
            typeChars(engine, "shijian")
            assertEquals("时间", engine.state.value.candidates.first().text)
            engine.clear()

            engine.setFixedCandidateOrder(false)
            assertFalse(engine.rimeBridge.getOption("fixed_candidate_order"))
            typeChars(engine, "shijian")
            assertEquals("实践", engine.state.value.candidates.first().text)
            engine.clear()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // 4. Rime Schema Package (.zip) Import, Real Assets & SHA-256 Download Verification
    // =========================================================================

    @Test
    fun rimeDataManager_extractsRealRimeIceAssetsAndEnforcesSha256Verification() {
        val (tempRoot, manager, engine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            // 1) Verify real iDvel/rime-ice assets were extracted including nested cn_dicts/
            for (assetPath in RimeDataManager.BUILTIN_ASSET_FILES) {
                val extracted = File(manager.sharedDir, assetPath)
                assertTrue("Expected built-in Rime asset $assetPath to exist", extracted.exists() && extracted.length() > 0L)
            }

            // 2) Verify pinned GitHub Release URL (no ghproxy.net, no mutable main branch)
            assertFalse(RimeDataManager.RIME_ICE_BASE_DICT_URL.contains("ghproxy"))
            assertFalse(RimeDataManager.RIME_ICE_BASE_DICT_URL.contains("/main/"))
            assertTrue(RimeDataManager.RIME_ICE_BASE_DICT_URL.contains(RimeDataManager.RIME_ICE_RELEASE_COMMIT))
            assertEquals(64, RimeDataManager.RIME_ICE_BASE_DICT_SHA256.length)

            // 3) Verify tampered stream with mismatched SHA-256 is rejected with SecurityException
            val sampleDictContent = """
                ---
                name: base
                version: "2026.06.30"
                sort: by_weight
                ...
                我们	wo men	1000
                今天天气	jin tian tian qi	500
            """.trimIndent()
            val sampleBytes = sampleDictContent.toByteArray(StandardCharsets.UTF_8)
            val realSha256 = MessageDigest.getInstance("SHA-256")
                .digest(sampleBytes)
                .joinToString("") { "%02x".format(it) }

            try {
                manager.installFullRimeIceFromStreamSync(
                    inputStream = ByteArrayInputStream(sampleBytes),
                    expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
                )
                fail("Expected SecurityException on SHA-256 mismatch")
            } catch (expected: SecurityException) {
                assertTrue(expected.message.orEmpty().contains("SHA-256"))
                assertTrue(manager.deploymentState.value is RimeDeploymentState.Error)
                assertFalse(manager.hasFullRimeIceDictionary())
            }

            // 4) Verify matching SHA-256 stream succeeds and marks full rime-ice installed
            val verifiedState = manager.installFullRimeIceFromStreamSync(
                inputStream = ByteArrayInputStream(sampleBytes),
                expectedSha256 = realSha256
            )
            assertTrue(verifiedState is RimeDeploymentState.Ready)
            assertTrue(manager.hasFullRimeIceDictionary())

            // 5) Verify .zip custom schema package import and CandidateStrip preedit left-truncation
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    zos.putNextEntry(ZipEntry("my_custom.schema.yaml"))
                    zos.write(
                        """
                        schema:
                          schema_id: my_custom
                          name: 极客自定义方案
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    )
                    zos.closeEntry()

                    zos.putNextEntry(ZipEntry("my_custom.dict.yaml"))
                    zos.write(
                        """
                        ---
                        name: my_custom
                        version: "1.0"
                        ...
                        反重力引擎	fan zhong li yin qing	999999
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    )
                    zos.closeEntry()
                }
                baos.toByteArray()
            }

            val deployState = manager.importRimeSchemaZipSync(ByteArrayInputStream(zipBytes))
            assertTrue(deployState is RimeDeploymentState.Ready)
            val ready = deployState as RimeDeploymentState.Ready
            assertEquals("极客自定义方案", ready.customSchemaName)

            engine.setInputScheme(InputScheme.RIME_CUSTOM)
            typeChars(engine, "fanzhongliyinqing")
            assertEquals("fan'zhong'li'yin'qing", engine.state.value.preedit)
            assertEquals("反重力引擎", engine.state.value.candidates.first().text)

            val longPreedit = "ni'hao'shi'jie'fan'zhong'li'yin'qing"
            val formattedRime = ImeLogic.formatPreeditDisplay(
                preedit = longPreedit,
                rawCode = "nihaoshijiefanzhongliyinqing",
                scheme = InputScheme.RIME_PINYIN,
                maxVisibleChars = 18
            )
            assertEquals("…" + longPreedit.takeLast(18), formattedRime)
            assertTrue(formattedRime.endsWith("yin'qing"))

            val formattedFlypy = ImeLogic.formatPreeditDisplay(
                preedit = longPreedit,
                rawCode = "uijm",
                scheme = InputScheme.FLYPY_TABLE,
                maxVisibleChars = 18
            )
            assertEquals("uijm", formattedFlypy)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
