package com.typefree.core.ime

import com.typefree.core.ime.enhancer.RimeCodeProber
import com.typefree.core.ime.importer.ImportMode
import com.typefree.core.ime.importer.SchemaPackageImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficialRawPackageImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val dummyProber = object : RimeCodeProber {
        override fun probeCandidates(code: String, maxCount: Int): List<String> = when (code) {
            "a" -> listOf("啊")
            "aa" -> listOf("啊啊")
            else -> emptyList()
        }
    }

    @Test
    fun `imports GBK encoded Windows raw official Xiaohe zip with full_code txt and quick symbols`() {
        val gbk = Charset.forName("GB18030")
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos, gbk).use { zos ->
            fun putBytes(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
            fun putText(path: String, text: String) = putBytes(path, text.toByteArray(StandardCharsets.UTF_8))

            val root = "小鹤音形Rime平台小狼毫for Windows/rime/"
            putText("${root}default.yaml", "schema_list:\n  - schema: flypy\n")
            putText("${root}weasel.custom.yaml", "patch: {}\n")
            putText("${root}installation.yaml", "distribution_code_name: Weasel\n")
            putText("${root}user.yaml", "var: {}\n")
            putText("${root}rime.lua", "function date_translator() end\n")
            putText("${root}lua/calculator_translator.lua", "return {}\n")
            putText(
                "${root}flypy.schema.yaml",
                """
                schema:
                  schema_id: flypy
                  name: 小鹤音形
                  version: "10.26.1"
                engine:
                  translators:
                    - table_translator
                    - lua_translator@date_translator
                    - table_translator@custom_phraseXT
                    - table_translator@custom_phraseQMZ
                speller:
                  alphabet: "abcdefghijklmnopqrstuvwxyz;'"
                  initials: ";abcdefghijklmnopqrstuvwxyz"
                  max_code_length: 4
                  auto_select: true
                  auto_select_pattern: "^;.$|^\\w{4}$"
                translator:
                  dictionary: flypy
                custom_phraseXT:
                  dictionary: ""
                  user_dict: flypy_sys
                  db_class: stabledb
                custom_phraseQMZ:
                  dictionary: ""
                  user_dict: flypy_full
                  db_class: stabledb
                """.trimIndent() + "\n"
            )
            putText("${root}flypy_full全码字.txt", "# coding: utf-8\n鹤\thepn\n")
            putText("${root}flypy_sys.txt", "# coding: utf-8\n：“\t;q\n★\t;xx\n")
            val fakeBin = ByteArray(64)
            "Rime::Table/4.0".toByteArray(StandardCharsets.US_ASCII).copyInto(fakeBin)
            putBytes("${root}build/flypy.table.bin", fakeBin)
        }

        val importer = SchemaPackageImporter(
            stagingParentDir = tempFolder.newFolder("staging"),
            targetSchemasRootDir = tempFolder.newFolder("schemas")
        )
        val result = importer.importZip(ByteArrayInputStream(baos.toByteArray()), overrideProber = dummyProber)

        assertEquals("flypy", result.schemaId)
        assertEquals(ImportMode.PRECOMPILED_BIN, result.importMode)
        assertTrue(File(result.installedDir, "build/flypy.table.bin").isFile)
        assertTrue(File(result.installedDir, "flypy_full.txt").readText().contains("鹤\thepn"))
        assertFalse(File(result.installedDir, "installation.yaml").exists())
        assertFalse(File(result.installedDir, "user.yaml").exists())
        assertFalse(File(result.installedDir, "rime.lua").exists())
        assertFalse(File(result.installedDir, "lua").exists())
        assertTrue(result.strippedDesktopFiles.contains("installation.yaml"))
        assertTrue(result.strippedDesktopFiles.contains("user.yaml"))
        assertTrue(result.quickSymbolTrie.entriesList().any { it.key == "xx" && it.displayText == "★" })
    }

    @Test
    fun `imports real official raw Xiaohe zips when fixture dir is provided and handles GBK plain dict zip`() {
        val fixtureDir = System.getenv("TYPEFREE_LOCAL_XIAOHE_FIXTURE_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
        if (fixtureDir != null && fixtureDir.isDirectory) {
            fixtureDir.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
                .orEmpty()
                .forEachIndexed { idx, zipFile ->
                    val importer = SchemaPackageImporter(
                        stagingParentDir = tempFolder.newFolder("staging_fixture_$idx"),
                        targetSchemasRootDir = tempFolder.newFolder("schemas_fixture_$idx")
                    )
                    val res = FileInputStream(zipFile).use { importer.importZip(it, overrideProber = dummyProber) }
                    assertEquals("flypy", res.schemaId)
                    assertEquals(ImportMode.PRECOMPILED_BIN, res.importMode)
                    assertTrue(File(res.installedDir, "build/flypy.table.bin").length() > 1_000_000L)
                    assertTrue(File(res.installedDir, "flypy_full.txt").length() > 10_000L)
                    assertFalse(File(res.installedDir, "squirrel.yaml").exists())
                    assertFalse(File(res.installedDir, "rime.lua").exists())
                    assertFalse(File(res.installedDir, "lua").exists())
                }
        }

        // Also verify plain-dict GBK Windows zip with stale desktop build/ directory
        val gbkPlain = ByteArrayOutputStream()
        ZipOutputStream(gbkPlain, Charset.forName("GB18030")).use { zos ->
            fun put(path: String, text: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(text.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            val root = "清凉鹤4.2挂接rime/"
            put("${root}installation.yaml", "distribution_code_name: Weasel\n")
            put("${root}user.yaml", "var: {}\n")
            put("${root}build/flypy.schema.yaml", "stale: true\n")
            put(
                "${root}flypy.schema.yaml",
                "schema:\n  schema_id: flypy\n  name: 小鹤音形\ntranslator:\n  dictionary: flypy\nspeller:\n  alphabet: abcdefghijklmnopqrstuvwxyz\n  max_code_length: 4\n"
            )
            put(
                "${root}flypy.dict.yaml",
                "---\nname: flypy\nversion: \"4.2\"\n...\n小鹤音形\txhyx\n实践\tuijm\n"
            )
        }
        val plainImporter = SchemaPackageImporter(
            stagingParentDir = tempFolder.newFolder("staging_gbk_plain"),
            targetSchemasRootDir = tempFolder.newFolder("schemas_gbk_plain")
        )
        val plainRes = plainImporter.importZip(ByteArrayInputStream(gbkPlain.toByteArray()), overrideProber = dummyProber)
        assertEquals("flypy", plainRes.schemaId)
        assertEquals(ImportMode.PLAIN_DICT_COMPILE, plainRes.importMode)
        assertFalse(File(plainRes.installedDir, "installation.yaml").exists())
        assertFalse(File(plainRes.installedDir, "user.yaml").exists())
        assertFalse(File(plainRes.installedDir, "build").exists())
    }
}
