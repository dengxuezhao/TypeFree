package com.typefree.core.ime

import com.typefree.core.ime.importer.CustomUserPhraseFileEditor
import com.typefree.core.ime.importer.ImportMode
import com.typefree.core.ime.importer.SchemaPackageImporter
import com.typefree.core.ime.importer.SubDictToggleManager
import com.typefree.core.ime.profile.SchemaArchetype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JiandaoAndSubDictImporterTest {

    private fun resolvePresetFile(fileName: String): File {
        val candidates = listOf(
            File("app/src/main/assets/preset_schemas/$fileName"),
            File("../app/src/main/assets/preset_schemas/$fileName")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing preset schema zip: $fileName (searched ${candidates.map { it.absolutePath }})")
    }

    @Test
    fun `importing xkjd6 keeps rime lua scripts and lua hooks while stripping desktop configs`() {
        val xkjd6Schema = """
            schema:
              schema_id: xkjd6
              name: 星空键道6
            engine:
              processors:
                - ascii_composer
                - lua_processor@topup_processor
                - speller
              segmentors:
                - lua_segmentor@custom_seg
                - abc_segmentor
              translators:
                - table_translator
                - lua_translator@number_translator
              filters:
                - uniquifier
                - lua_filter@keytao_filter
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz"
            translator:
              dictionary: xkjd6
              enable_sentence: false
              enable_user_dict: false
            topup:
              topup_with: "aeiovu"
              min_length: 4
              max_length: 6
        """.trimIndent()

        val xkjd6Dict = """
            ---
            name: xkjd6
            version: "6.0"
            sort: by_weight
            use_preset_vocabulary: false
            ...
            是	e	10
            星	xgoi	10
            星空	xgoiky	10
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                put("rime/xkjd6.schema.yaml", xkjd6Schema)
                put("rime/xkjd6.dict.yaml", xkjd6Dict)
                put("rime/rime.lua", "topup_processor = require(\"topup_processor\")\n")
                put("rime/lua/topup_processor.lua", "return { init = function() end, func = function() return 2 end }\n")
                put("rime/squirrel.yaml", "style: {}\n")
                put("rime/weasel.custom.yaml", "patch: {}\n")
                put("rime/xkjd6.trime.yaml", "name: xkjd6\n")
            }
            baos.toByteArray()
        }

        val tempRoot = Files.createTempDirectory("test_xkjd6_").toFile()
        try {
            val importer = SchemaPackageImporter(
                stagingParentDir = File(tempRoot, "staging"),
                targetSchemasRootDir = File(tempRoot, "schemas")
            )
            val result = importer.importZip(ByteArrayInputStream(zipBytes))

            assertEquals("xkjd6", result.schemaId)
            assertEquals(ImportMode.PLAIN_DICT_COMPILE, result.importMode)
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, result.profile.archetype)
            assertFalse(result.profile.reverseSplitEnabled)
            assertFalse(result.profile.fifthKeyTopCommit)
            assertTrue(result.profile.lockNaturalOrder)
            assertTrue(result.sanitizedLuaTranslators.isEmpty())

            assertTrue(File(result.installedDir, "rime.lua").isFile)
            assertTrue(File(result.installedDir, "lua/topup_processor.lua").isFile)
            assertFalse(File(result.installedDir, "squirrel.yaml").exists())
            assertFalse(File(result.installedDir, "weasel.custom.yaml").exists())
            assertFalse(File(result.installedDir, "xkjd6.trime.yaml").exists())
            assertTrue("squirrel.yaml" in result.strippedDesktopFiles)
            assertTrue("weasel.custom.yaml" in result.strippedDesktopFiles)
            assertTrue("xkjd6.trime.yaml" in result.strippedDesktopFiles)
            assertFalse("rime.lua" in result.strippedDesktopFiles)

            val installedSchema = File(result.installedDir, "xkjd6.schema.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(installedSchema.contains("- lua_processor@topup_processor"))
            assertTrue(installedSchema.contains("- lua_segmentor@custom_seg"))
            assertTrue(installedSchema.contains("- lua_translator@number_translator"))
            assertTrue(installedSchema.contains("- lua_filter@keytao_filter"))
            assertFalse(installedSchema.contains("[TypeFree DirectCommand]"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `bundled preset zips xkjd6_preset and wubi86_preset import cleanly with valid metadata`() {
        val xkjd6Zip = resolvePresetFile("xkjd6_preset.zip")
        val wubi86Zip = resolvePresetFile("wubi86_preset.zip")
        assertTrue(
            "Combined preset zips must stay compact (< 4 MB)",
            xkjd6Zip.length() + wubi86Zip.length() < 4L * 1024L * 1024L
        )

        val tempRoot = Files.createTempDirectory("test_presets_").toFile()
        try {
            val importer = SchemaPackageImporter(
                stagingParentDir = File(tempRoot, "staging"),
                targetSchemasRootDir = File(tempRoot, "schemas")
            )

            val xkjdResult = xkjd6Zip.inputStream().use { importer.importZip(it) }
            assertEquals("xkjd6", xkjdResult.schemaId)
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, xkjdResult.profile.archetype)
            assertTrue(File(xkjdResult.installedDir, "rime.lua").isFile)
            assertTrue(File(xkjdResult.installedDir, "lua/topup_processor.lua").isFile)
            assertTrue(File(xkjdResult.installedDir, "LICENSE").isFile)
            val xkjdSchemaText = File(xkjdResult.installedDir, "xkjd6.schema.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(xkjdSchemaText.contains("- lua_processor@topup_processor"))
            val xkjdDictText = File(xkjdResult.installedDir, "xkjd6.dict.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(xkjdDictText.contains("name: xkjd6"))
            assertTrue(xkjdDictText.contains("use_preset_vocabulary: false"))

            val wubiResult = wubi86Zip.inputStream().use { importer.importZip(it) }
            assertEquals("wubi86", wubiResult.schemaId)
            assertEquals(SchemaArchetype.FIXED_SHAPECODE, wubiResult.profile.archetype)
            assertEquals(4, wubiResult.profile.maxCodeLength)
            assertTrue(wubiResult.profile.fifthKeyTopCommit)
            assertFalse(wubiResult.profile.reverseSplitEnabled)
            val wubiDictText = File(wubiResult.installedDir, "wubi86.dict.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(wubiDictText.contains("五\tgg"))
            assertTrue(wubiDictText.contains("王\tgggg"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `importing flypy still strips rime lua and comments out lua hooks`() {
        val flypySchema = """
            schema:
              schema_id: flypy
              name: 小鹤音形
            engine:
              processors:
                - ascii_composer
                - lua_processor@legacy_proc
                - speller
              translators:
                - lua_translator@date_translator
                - table_translator
              filters:
                - lua_filter@legacy_filter
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz;'"
              max_code_length: 4
            translator:
              dictionary: flypy
              enable_sentence: false
              enable_user_dict: false
        """.trimIndent()

        val flypyDict = buildString {
            appendLine("---")
            appendLine("name: flypy")
            appendLine("...")
            appendLine("啊\ta")
            for (c1 in 'a'..'j') {
                for (c2 in 'a'..'z') {
                    appendLine("词$c1$c2\t$c1$c2")
                }
            }
        }

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                put("flypy.schema.yaml", flypySchema)
                put("flypy.dict.yaml", flypyDict)
                put("rime.lua", "-- legacy xiaohe rime.lua\n")
            }
            baos.toByteArray()
        }

        val tempRoot = Files.createTempDirectory("test_flypy_strip_").toFile()
        try {
            val importer = SchemaPackageImporter(
                stagingParentDir = File(tempRoot, "staging"),
                targetSchemasRootDir = File(tempRoot, "schemas")
            )
            val result = importer.importZip(ByteArrayInputStream(zipBytes))
            assertEquals(SchemaArchetype.FIXED_YINXING, result.profile.archetype)
            assertFalse(File(result.installedDir, "rime.lua").exists())
            assertTrue("rime.lua" in result.strippedDesktopFiles)
            assertEquals(3, result.sanitizedLuaTranslators.size)

            val installedSchema = File(result.installedDir, "flypy.schema.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(installedSchema.contains("# [TypeFree DirectCommand] - lua_processor@legacy_proc"))
            assertTrue(installedSchema.contains("# [TypeFree DirectCommand] - lua_translator@date_translator"))
            assertTrue(installedSchema.contains("# [TypeFree DirectCommand] - lua_filter@legacy_filter"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SubDictToggleManager imports tsv and dict yaml, toggles state, and builds reverse lookup`() {
        val tempDir = Files.createTempDirectory("test_subdict_ext_").toFile()
        try {
            File(tempDir, "flypy_full.dict.yaml").writeText("---\nname: flypy_full\n...\n王\twhua\n", StandardCharsets.UTF_8)
            val reloaded = mutableListOf<String>()
            val mgr = SubDictToggleManager(tempDir) { reloaded.add(it) }

            val initial = mgr.listAvailableSubDicts("flypy")
            assertEquals(1, initial.size)
            assertEquals("flypy_full", initial[0].name)
            assertTrue(initial[0].isBuiltIn)
            assertTrue(initial[0].enabled)

            // 1) Import code\tword TSV with unsafe path chars
            val tsvContent = """
                # medical lexicon
                yxqx	心肌梗死
                nzzf	脑卒中
            """.trimIndent()
            val medDesc = mgr.importExtDict(
                sourceFileName = "../../medical terms!.tsv",
                contentStream = ByteArrayInputStream(tsvContent.toByteArray(StandardCharsets.UTF_8)),
                schemaId = "flypy"
            )
            assertEquals("ext/medical_terms", medDesc.name)
            assertEquals("ext/medical_terms.dict.yaml", medDesc.relativePath)
            assertTrue(medDesc.enabled)
            assertFalse(medDesc.isBuiltIn)

            val medText = File(tempDir, "ext/medical_terms.dict.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(medText.contains("name: ext/medical_terms"))
            assertTrue(medText.contains("心肌梗死\tyxqx"))
            assertTrue(medText.contains("脑卒中\tnzzf"))

            // 2) Import word\tcode .dict.yaml and normalize name header
            val yamlDict = """
                ---
                name: old_legal_name
                version: "1.0"
                ...
                民法典	mfdn
                行政诉讼	xzss	100
            """.trimIndent()
            val legalDesc = mgr.importExtDict(
                sourceFileName = "legal.dict.yaml",
                contentStream = ByteArrayInputStream(yamlDict.toByteArray(StandardCharsets.UTF_8)),
                schemaId = "flypy"
            )
            assertEquals("ext/legal", legalDesc.name)
            val legalText = File(tempDir, "ext/legal.dict.yaml").readText(StandardCharsets.UTF_8)
            assertTrue(legalText.contains("name: ext/legal"))
            assertFalse(legalText.contains("old_legal_name"))

            // Both enabled in reverse lookup
            val lookup1 = mgr.loadEnabledExtReverseLookup("flypy")
            assertEquals(listOf("yxqx"), lookup1["心肌梗死"])
            assertEquals(listOf("mfdn"), lookup1["民法典"])

            // Disable ext/medical_terms and verify persistence + reverse lookup filtering
            mgr.setSubDictEnabled("flypy", "ext/medical_terms", false)
            assertTrue(reloaded.isNotEmpty())

            val mgrReloaded = SubDictToggleManager(tempDir)
            val listed = mgrReloaded.listAvailableSubDicts("flypy")
            val medReloaded = listed.firstOrNull { it.name == "ext/medical_terms" }
            val legalReloaded = listed.firstOrNull { it.name == "ext/legal" }
            assertNotNull(medReloaded)
            assertNotNull(legalReloaded)
            assertFalse(medReloaded!!.enabled)
            assertTrue(legalReloaded!!.enabled)

            val lookup2 = mgrReloaded.loadEnabledExtReverseLookup("flypy")
            assertFalse("心肌梗死" in lookup2)
            assertEquals(listOf("mfdn"), lookup2["民法典"])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `CustomUserPhraseFileEditor lists, adds, deduplicates, validates, and removes phrases preserving headers`() {
        val tempDir = Files.createTempDirectory("test_user_phrase_").toFile()
        try {
            val topFile = File(tempDir, "xkjd6_top.txt")
            topFile.writeText(
                """
                # Rime table
                # coding: utf-8
                #@/db_name	xkjd6_top
                #@/db_type	stabledb
                # custom comment header
                置顶词	zdc
                """.trimIndent() + "\n",
                StandardCharsets.UTF_8
            )

            val editor = CustomUserPhraseFileEditor(tempDir, "xkjd6")
            val initialTop = editor.listPhrases(isTopPinned = true)
            assertEquals(1, initialTop.size)
            assertEquals(CustomUserPhraseFileEditor.UserPhraseEntry("置顶词", "zdc", true), initialTop[0])
            assertTrue(editor.listPhrases(isTopPinned = false).isEmpty())

            // Add + deduplicate
            editor.addPhrase("测试词", "cuc", isTopPinned = true)
            editor.addPhrase("测试词", "cuc", isTopPinned = true)
            assertEquals(2, editor.listPhrases(isTopPinned = true).size)

            editor.addPhrase("自造词", "zzc", isTopPinned = false)
            assertEquals(1, editor.listPhrases(isTopPinned = false).size)
            assertTrue(File(tempDir, "xkjd6_user.txt").isFile)

            // Reject blank / invalid control chars
            var rejectedBlank = false
            try {
                editor.addPhrase("  ", "abc", isTopPinned = false)
            } catch (_: IllegalArgumentException) {
                rejectedBlank = true
            }
            assertTrue(rejectedBlank)

            var rejectedTab = false
            try {
                editor.addPhrase("bad\tword", "abc", isTopPinned = false)
            } catch (_: IllegalArgumentException) {
                rejectedTab = true
            }
            assertTrue(rejectedTab)

            var rejectedNewline = false
            try {
                editor.addPhrase("good", "a\nb", isTopPinned = false)
            } catch (_: IllegalArgumentException) {
                rejectedNewline = true
            }
            assertTrue(rejectedNewline)

            // Remove & verify header preservation
            assertTrue(editor.removePhrase("置顶词", "zdc", isTopPinned = true))
            assertFalse(editor.removePhrase("不存在", "none", isTopPinned = true))
            val remaining = editor.listPhrases(isTopPinned = true)
            assertEquals(1, remaining.size)
            assertEquals("测试词", remaining[0].word)

            val topContent = topFile.readText(StandardCharsets.UTF_8)
            assertTrue(topContent.contains("# custom comment header"))
            assertTrue(topContent.contains("#@/db_name\txkjd6_top"))
            assertFalse(topContent.contains("置顶词\tzdc"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
