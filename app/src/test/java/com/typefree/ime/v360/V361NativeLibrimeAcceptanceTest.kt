package com.typefree.ime.v360

import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.ime.data.InputScheme
import com.typefree.ime.ime.engine.CodetableEnhancedInputEngine
import com.typefree.ime.ime.engine.EngineResult
import com.typefree.ime.ime.engine.KeyInput
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.rime.RimeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Host macOS real `librime` + `librime-lua` JNI acceptance tests for v3.6.1 codetable enhancements.
 */
class V361NativeLibrimeAcceptanceTest {

    private fun resolveXiaoheAssetsDir(): File? {
        return listOf(
            File("src/xiaohe/assets/rime"),
            File("app/src/xiaohe/assets/rime")
        ).firstOrNull { File(it, "flypy.schema.yaml").exists() }
    }

    private fun resolveMainRimeAssetsDir(): File? {
        return listOf(
            File("src/main/assets/rime"),
            File("app/src/main/assets/rime")
        ).firstOrNull { File(it, "default.yaml").exists() }
    }

    private fun resolvePresetZip(fileName: String): File? {
        return listOf(
            File("src/main/assets/preset_schemas/$fileName"),
            File("app/src/main/assets/preset_schemas/$fileName")
        ).firstOrNull { it.isFile }
    }

    private fun loadNativeBridgeOrSkip(): RimeBridge? {
        val nativeDylibPresent = listOf(
            File("rime/build/host-macos/librime_jni.dylib"),
            File("../rime/build/host-macos/librime_jni.dylib")
        ).any { it.isFile }
        val bridge = RimeBridge()
        if (!bridge.isNativeLoaded) {
            if (nativeDylibPresent) {
                throw AssertionError("Host librime_jni.dylib exists but failed to load in RimeBridge")
            }
            return null
        }
        return bridge
    }

    private fun typeChars(engine: CodetableEnhancedInputEngine, keys: String): EngineResult? {
        var last: EngineResult? = null
        for (ch in keys) {
            last = engine.onKey(KeyInput.CharKey(ch))
        }
        return last
    }

    @Test
    fun `Real native librime flypy verifies v361 enhancements end to end`() {
        val xiaoheDir = resolveXiaoheAssetsDir() ?: return
        val mainDir = resolveMainRimeAssetsDir() ?: return
        val bridge = loadNativeBridgeOrSkip() ?: return

        val tempRoot = Files.createTempDirectory("native_v361_flypy_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            mainDir.copyRecursively(sharedDir, overwrite = true)
            xiaoheDir.copyRecursively(sharedDir, overwrite = true)

            val dm = RimeDataManager(sharedDir, userDir, bridge)
            val deployState = dm.deployDefaultSync(InputScheme.FLYPY_TABLE)
            assertTrue("Expected Ready state, got $deployState", deployState is RimeDeploymentState.Ready)
            assertTrue(dm.activateEnhancedSchemeSync(InputScheme.FLYPY_TABLE))

            val engine = CodetableEnhancedInputEngine(
                rimeClient = bridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                schemaDir = sharedDir
            )
            engine.setEnglishPrefixVetoEnabled(true)
            engine.setAutoEncodeEnabled(false)

            // 1) n -> [你, 内容]
            typeChars(engine, "n")
            assertEquals(listOf("你", "内容"), engine.state.value.candidates.map { it.text })
            engine.clear()

            // 2) okhg natural continuation hoists 丁〔~uu〕 ahead of Rime ASCII completions (百~bd/下~bu)
            typeChars(engine, "okhg")
            val okhgCands = engine.state.value.candidates
            assertTrue("Expected okhg candidates, got $okhgCands", okhgCands.isNotEmpty())
            assertEquals("丁", okhgCands[0].text)
            assertEquals("~uu", okhgCands[0].codeHint)
            engine.clear()

            // 3) okhguu -> exact 丁
            typeChars(engine, "okhguu")
            assertTrue("Expected 丁 for okhguu", engine.state.value.candidates.any { it.text == "丁" })
            engine.clear()

            // 4) hfkn -> reverse split 很可能 / 困难
            typeChars(engine, "hfkn")
            val hfknCands = engine.state.value.candidates
            assertTrue(hfknCands.isNotEmpty())
            assertEquals("很可能", hfknCands[0].text)
            assertTrue(hfknCands.any { it.text == "很困难" && it.displayText == "困难" })
            engine.clear()

            // 5) gith / goog -> EnglishWordTrie veto keeps 4+ letters and suggests github / google, while pyth preserves Chinese
            typeChars(engine, "gith")
            val githCands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected github for gith, got $githCands", "github" in githCands)
            engine.clear()

            typeChars(engine, "goog")
            val googCands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected google for goog (must not auto-commit 3-code 腘), got $googCands", "google" in googCands)
            engine.clear()

            val pythRes = typeChars(engine, "pyth")
            val pythMatched = pythRes == EngineResult.Commit("跑一趟", remainComposing = false) ||
                engine.state.value.candidates.any { it.text == "跑一趟" }
            assertTrue("Expected exact 4-code 跑一趟 for pyth, got res=$pythRes cands=${engine.state.value.candidates}", pythMatched)
            engine.clear()

            // 6) orq+1 -> tomorrow's date
            typeChars(engine, "orq+1")
            val expectedTomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy年MM月dd日", Locale.CHINA))
            val orqCands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected tomorrow $expectedTomorrow for orq+1, got $orqCands", expectedTomorrow in orqCands)
            engine.clear()

            // 7) odz -> toggles single-char mode
            typeChars(engine, "odz")
            val odzCands = engine.state.value.candidates
            assertEquals("开启单字模式", odzCands[0].displayText)
            engine.select(0)
            assertTrue(engine.singleCharModeEnabled)
            typeChars(engine, "n")
            assertEquals(listOf("你"), engine.state.value.candidates.map { it.text })
            engine.clear()
            typeChars(engine, "odz")
            assertEquals("关闭单字模式", engine.state.value.candidates[0].displayText)
            engine.select(0)
            assertFalse(engine.singleCharModeEnabled)

            // 8) zz / zza multi-level symbols
            typeChars(engine, "zz")
            assertTrue(engine.state.value.candidates.any { it.displayText == "zza 常用符号" })
            typeChars(engine, "a")
            assertTrue(engine.state.value.candidates.any { it.text == "★" })
            engine.clear()

            // 9) shadow.yaml pin & block
            typeChars(engine, "n")
            engine.pinCandidate("内容")
            assertEquals(listOf("内容", "你"), engine.state.value.candidates.map { it.text })
            engine.blockCandidate("你")
            assertEquals(listOf("内容"), engine.state.value.candidates.map { it.text })
            engine.clear()
            engine.shadowDictManager?.clearAll()
            engine.reloadShadowRules()

            // 10) AaAbBaBb auto-encode when toggled ON
            engine.setAutoEncodeEnabled(true)
            typeChars(engine, "vg")
            val r1 = engine.select(0) // 正
            assertEquals(EngineResult.Commit("正", remainComposing = false), r1)
            typeChars(engine, "bk")
            val r2 = engine.select(0) // 并
            assertEquals(EngineResult.Commit("并", remainComposing = false), r2)
            val userFile = File(sharedDir, "flypy_user.txt")
            assertTrue(
                "Expected flypy_user.txt to contain 正并\\tvgbk\\t1",
                userFile.readText(StandardCharsets.UTF_8).contains("正并\tvgbk\t1")
            )
            val autoRes = typeChars(engine, "vgbk")
            // If unique 4-code auto-commits or sits at index 0, verify either outcome
            val matchedAuto = (autoRes as? EngineResult.Commit)?.text == "正并" ||
                engine.state.value.candidates.any { it.text == "正并" }
            assertTrue("Expected 正并 via vgbk after auto-encode", matchedAuto)
            engine.clear()
            engine.setAutoEncodeEnabled(false)

            // 11) ojf OpenCC simplification toggle
            assertFalse(bridge.getOption("simplification"))
            typeChars(engine, "ojf")
            engine.select(0)
            assertTrue(bridge.getOption("simplification"))
            typeChars(engine, "n")
            assertTrue("內容" in engine.state.value.candidates.map { it.text })
            engine.clear()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `Real native librime natural continuation qfx surfaces 清风徐来 ~l when qfx has no exact match`() {
        val mainDir = resolveMainRimeAssetsDir() ?: return
        val bridge = loadNativeBridgeOrSkip() ?: return

        val tempRoot = Files.createTempDirectory("native_v361_qfx_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            mainDir.copyRecursively(sharedDir, overwrite = true)

            val schemaYaml = """
                schema:
                  schema_id: flypy
                  name: 小鹤音形测试
                engine:
                  processors:
                    - ascii_composer
                    - speller
                    - punctuator
                    - selector
                    - navigator
                    - express_editor
                  segmentors:
                    - ascii_segmentor
                    - abc_segmentor
                    - punct_segmentor
                    - fallback_segmentor
                  translators:
                    - punct_translator
                    - table_translator
                speller:
                  alphabet: "abcdefghijklmnopqrstuvwxyz'"
                  max_code_length: 4
                  auto_select: false
                translator:
                  dictionary: flypy
                  enable_sentence: false
                  enable_user_dict: false
                  enable_completion: true
            """.trimIndent()
            val dictYaml = """
                ---
                name: flypy
                version: "1.0"
                sort: original
                use_preset_vocabulary: false
                ...
                清风徐来	qfxl
                秋风萧瑟	qfxs
                丁	okhguu
                百	okhgbd
            """.trimIndent()
            val toml = """
                schema_id = "flypy"
                display_name = "小鹤音形测试"
                archetype = "FIXED_YINXING"
                max_code_length = 4
                fifth_key_top_commit = true
                auto_commit_unique_full = true
                reverse_split_enabled = true
                gt4_guard_prefixes = ["ok", "os"]
            """.trimIndent()

            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                    zos.putNextEntry(ZipEntry("flypy.schema.yaml"))
                    zos.write(schemaYaml.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("flypy.dict.yaml"))
                    zos.write(dictYaml.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("typefree.toml"))
                    zos.write(toml.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                baos.toByteArray()
            }

            val dm = RimeDataManager(sharedDir, userDir, bridge)
            val imported = dm.importEnhancedSchemaPackageSync(ByteArrayInputStream(zipBytes))
            assertEquals("flypy", imported.schemaId)

            val engine = CodetableEnhancedInputEngine(
                rimeClient = bridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                schemaDir = sharedDir
            )
            typeChars(engine, "qfx")
            val cands = engine.state.value.candidates
            assertTrue("Expected qfx candidates, got $cands", cands.isNotEmpty())
            assertEquals("清风徐来", cands[0].text)
            assertEquals("~l", cands[0].codeHint)
            assertEquals(EngineResult.Commit("清风徐来", remainComposing = false), engine.select(0))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `Real native librime-lua deploys xkjd6_preset zip and topup_processor produces JianDao 6 candidates`() {
        val mainDir = resolveMainRimeAssetsDir() ?: return
        val xkjdZip = resolvePresetZip("xkjd6_preset.zip") ?: return
        val bridge = loadNativeBridgeOrSkip() ?: return

        val tempRoot = Files.createTempDirectory("native_v361_xkjd6_").toFile()
        try {
            val sharedDir = File(tempRoot, "shared").apply { mkdirs() }
            val userDir = File(tempRoot, "user").apply { mkdirs() }
            mainDir.copyRecursively(sharedDir, overwrite = true)

            val dm = RimeDataManager(sharedDir, userDir, bridge)
            val result = xkjdZip.inputStream().use { stream ->
                dm.importEnhancedSchemaPackageSync(stream)
            }
            assertEquals("xkjd6", result.schemaId)
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, result.profile.archetype)
            assertTrue("rime.lua must be preserved", File(sharedDir, "rime.lua").isFile)
            assertTrue("lua/topup_processor.lua must be preserved", File(sharedDir, "lua/topup_processor.lua").isFile)
            val deployedSchema = File(sharedDir, "xkjd6.schema.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(
                "xkjd6.schema.yaml must preserve lua_processor@topup_processor",
                deployedSchema.contains("- lua_processor@topup_processor")
            )

            // Verify real librime-lua + table_translator decodes JianDao 6 codes and topup_processor commits on follow-up consonant
            assertTrue("bridge.deploy(xkjd6) must succeed", bridge.deploy("xkjd6"))
            assertTrue(bridge.selectSchema("xkjd6"))
            val engine = CodetableEnhancedInputEngine(
                rimeClient = bridge,
                initialProfile = dm.activeSchemaProfile,
                symbolTrie = dm.activeSymbolTrie,
                briefIndex = dm.activeBriefIndex,
                reverseLookupMap = dm.activeReverseLookupMap,
                schemaDir = sharedDir
            )
            typeChars(engine, "xgoi")
            val cands = engine.state.value.candidates.map { it.text }
            assertTrue("Expected 星 for xgoi in JianDao 6, got $cands", "星" in cands)

            // Typing consonant `k` after `xgoi` triggers Lua topup_processor auto-commit of `星`
            val topupRes = engine.onKey(KeyInput.CharKey('k'))
            assertTrue(
                "Expected topup auto-commit on follow-up consonant after xgoi, got $topupRes",
                topupRes is EngineResult.Commit && topupRes.text == "星" && topupRes.remainComposing
            )
            engine.clear()
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
