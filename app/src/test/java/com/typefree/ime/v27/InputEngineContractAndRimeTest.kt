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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

    private fun createTempRimeSetup(
        scheme: InputScheme = InputScheme.RIME_PINYIN
    ): Triple<File, RimeDataManager, RimeInputEngine> {
        val tempRoot = Files.createTempDirectory("typefree_rime_test_").toFile()
        val sharedDir = File(tempRoot, "rime/shared")
        val userDir = File(tempRoot, "rime/user")
        val bridge = RimeBridge()
        val manager = RimeDataManager(
            sharedDir = sharedDir,
            userDir = userDir,
            rimeBridge = bridge,
            appContext = null
        )
        val deployState = manager.deployDefaultSync(scheme)
        assertTrue(
            "Expected Rime default deployment to succeed, got $deployState",
            deployState is RimeDeploymentState.Ready
        )
        val engine = RimeInputEngine(rimeBridge = bridge, initialScheme = scheme)
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

            // 2) Full Pinyin sentence `nihaoshijie` -> segmented preedit `ni'hao'shi'jie` & `你好世界`
            typeChars(engine, "nihaoshijie")
            assertEquals("ni'hao'shi'jie", engine.state.value.preedit)
            val sentenceCands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected 你好世界 in $sentenceCands", "你好世界" in sentenceCands)
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
    // 3. UserDB Frequency Boosting, Custom Phrase Learning, Fixed Order & Incognito
    // =========================================================================

    @Test
    fun rimeUserDb_supportsFrequencyBoostingCustomPhraseFixedOrderAndStrictIncognito() {
        val (tempRoot, manager, engine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            val userDbFile = File(manager.userDir, "rime_ice.userdb.tsv")
            assertFalse("UserDB file should not exist before any commit", userDbFile.exists())

            // 1) Incognito mode (`setIncognitoMode(true)`): must NEVER write to userdb or alter order
            engine.setIncognitoMode(true)
            typeChars(engine, "shijian")
            val initialOrder = engine.state.value.candidates.map { it.text }
            val shijianIdx = initialOrder.indexOf("实践")
            assertTrue("Expected 实践 in candidates: $initialOrder", shijianIdx >= 1)
            assertEquals("时间", initialOrder[0])

            // Select "实践" while in incognito mode
            assertEquals(EngineResult.Commit("实践", false), engine.select(shijianIdx))
            assertFalse("Incognito commit must NOT create userdb file", userDbFile.exists())

            // Next time we type "shijian", "时间" is still #1 because incognito suppressed learning
            typeChars(engine, "shijian")
            assertEquals("时间", engine.state.value.candidates.first().text)
            engine.clear()

            // 2) Turn off incognito mode -> selecting "实践" boosts it to #1 and persists to userdb!
            engine.setIncognitoMode(false)
            typeChars(engine, "shijian")
            val idxToBoost = engine.state.value.candidates.indexOfFirst { it.text == "实践" }
            assertTrue(idxToBoost >= 1)
            assertEquals(EngineResult.Commit("实践", false), engine.select(idxToBoost))
            assertTrue("Normal commit must persist userdb file", userDbFile.exists())

            // Verify "实践" is now promoted to 1st candidate
            typeChars(engine, "shijian")
            assertEquals("实践", engine.state.value.candidates.first().text)
            engine.clear()

            // 3) Fixed Candidate Order (`setFixedCandidateOrder(true)`): locks static order ("时间" #1)
            engine.setFixedCandidateOrder(true)
            typeChars(engine, "shijian")
            assertEquals("时间", engine.state.value.candidates.first().text)
            engine.clear()

            // Re-enable dynamic order -> "实践" returns to #1
            engine.setFixedCandidateOrder(false)
            typeChars(engine, "shijian")
            assertEquals("实践", engine.state.value.candidates.first().text)
            engine.clear()

            // 4) Custom phrase learning (`自造词`)
            assertTrue(engine.learnCustomPhrase("ce shi zi zao ci", "测试自造词"))
            typeChars(engine, "ceshizizaoci")
            assertEquals("ce'shi'zi'zao'ci", engine.state.value.preedit)
            assertEquals("测试自造词", engine.state.value.candidates.first().text)
            engine.clear()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // 4. Rime Schema Package (.zip) Import & Preedit Left-Truncation
    // =========================================================================

    @Test
    fun rimeDataManager_importsCustomSchemaZipAndPreeditTruncatesFromLeft() {
        val (tempRoot, manager, engine) = createTempRimeSetup(InputScheme.RIME_PINYIN)
        try {
            // Build an in-memory .zip containing a custom .schema.yaml and .dict.yaml
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

            // Verify CandidateStrip preedit left-truncation (`"…" + preedit.takeLast(18)`)
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
