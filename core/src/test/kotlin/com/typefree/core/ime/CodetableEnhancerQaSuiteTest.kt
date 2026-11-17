package com.typefree.core.ime

import com.typefree.core.ime.coordinator.CommitAction
import com.typefree.core.ime.coordinator.CoordinatorKeyOutcome
import com.typefree.core.ime.coordinator.RimeCoreSessionPort
import com.typefree.core.ime.coordinator.RimeDecodeFrame
import com.typefree.core.ime.coordinator.TypeFreeKeyCoordinator
import com.typefree.core.ime.enhancer.BriefPrefixIndex
import com.typefree.core.ime.enhancer.DirectCommandEngine
import com.typefree.core.ime.enhancer.ReverseSplitEngine
import com.typefree.core.ime.enhancer.Utf8Safety
import com.typefree.core.ime.importer.ImportMode
import com.typefree.core.ime.importer.SchemaPackageImporter
import com.typefree.core.ime.overlay.OverlaySymbolController
import com.typefree.core.ime.overlay.SymbolTrie
import com.typefree.core.ime.profile.SchemaArchetype
import com.typefree.core.ime.profile.SchemaInspector
import com.typefree.core.ime.profile.SchemaProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Automated QA Acceptance Gate Suite covering Section 6.2 (`QA-OVERLAY-01` .. `QA-IMPORTER-BIN-01`)
 * of `TypeFree 多方案码表增强引擎与开放导入架构技术设计方案` (v1.1).
 *
 * Automatically binds to injected `app/src/xiaohe/assets/rime/` real dictionary files when present,
 * with deterministic self-contained fallback fixtures for clean open-source CI runs.
 */
class CodetableEnhancerQaSuiteTest {

    private class InMemoryTableRimePort(
        private val codeMap: Map<String, List<String>>,
        private val pushTopupMode: Boolean = false
    ) : RimeCoreSessionPort {
        private val buf = StringBuilder()
        private val options = HashMap<String, Boolean>()
        var userDictMutationBytes: Long = 0L

        override fun sendChar(ch: Char): RimeDecodeFrame {
            // Simulate Keytao 6 native 3-code character push-topup when stroke `[aiouv]` is followed by initial
            if (pushTopupMode && buf.length == 3 && buf[2] in "aiouv" && ch !in "aiouv") {
                val prev = buf.toString()
                val top = codeMap[prev]?.firstOrNull()
                if (top != null) {
                    buf.clear()
                    buf.append(ch)
                    val nextCands = codeMap[buf.toString()].orEmpty().map { it to "" }
                    return RimeDecodeFrame(
                        rawInput = buf.toString(),
                        preedit = buf.toString(),
                        candidates = nextCands,
                        autoCommittedText = top
                    )
                }
            }
            buf.append(ch)
            return currentFrame()
        }

        override fun sendBackspace(): RimeDecodeFrame {
            if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            return currentFrame()
        }

        override fun selectCandidate(index: Int): Pair<String?, RimeDecodeFrame> {
            val cands = currentFrame().candidates
            val picked = cands.getOrNull(index)?.first
            if (picked != null && options["enable_user_dict"] == true && options["_incognito"] != true) {
                userDictMutationBytes += picked.toByteArray(StandardCharsets.UTF_8).size
            }
            buf.clear()
            return picked to currentFrame()
        }

        override fun commitCurrentComposition(): String? {
            val top = currentFrame().candidates.firstOrNull()?.first
            buf.clear()
            return top
        }

        override fun clearComposition() {
            buf.clear()
        }

        override fun currentFrame(maxCandidates: Int): RimeDecodeFrame {
            val raw = buf.toString()
            if (raw.isEmpty()) return RimeDecodeFrame()
            val list = codeMap[raw].orEmpty().take(maxCandidates).map { it to "" }
            return RimeDecodeFrame(
                rawInput = raw,
                preedit = raw,
                candidates = list
            )
        }

        override fun setOption(option: String, value: Boolean) {
            options[option] = value
        }

        override fun getOption(option: String): Boolean = options[option] ?: false
    }

    private fun resolveXiaoheAssetDir(): File? {
        return listOf(
            File("app/src/xiaohe/assets/rime"),
            File("../app/src/xiaohe/assets/rime")
        ).firstOrNull { File(it, "flypy.dict.yaml").exists() }
    }

    private fun loadXiaoheDictAndIndex(): Pair<Map<String, List<String>>, BriefPrefixIndex> {
        val realDir = resolveXiaoheAssetDir()
        if (realDir != null) {
            val dictFile = File(realDir, "flypy.dict.yaml")
            val map = LinkedHashMap<String, MutableList<String>>(180_000)
            var inBody = false
            dictFile.forEachLine(StandardCharsets.UTF_8) { line ->
                val t = line.trim()
                if (!inBody) {
                    if (t == "...") inBody = true
                } else if (t.isNotEmpty() && !t.startsWith("#")) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) {
                        val word = line.substring(0, tab).trim()
                        val code = line.substring(tab + 1).substringBefore('\t').trim()
                        map.getOrPut(code) { ArrayList(2) }.add(word)
                    }
                }
            }
            val index = BriefPrefixIndex.buildFromDictLines(
                mainDictLines = dictFile.readLines(StandardCharsets.UTF_8).asSequence(),
                gt4GuardTwoPrefixes = setOf("ok", "os")
            )
            return map to index
        }

        // Fallback minimal fixture matching real flypy.dict.yaml v10.26.3 rows verbatim
        val sampleDict = """
            ---
            name: flypy
            version: "10.26.3"
            sort: original
            ...
            啊	a
            安装	a
            安	an
            按	an
            很	hf
            可能	kn
            困难	kn
            什么	sm
            👌	ok
            很高	hg
            丁	okhguu
            丅	okhguu
            趋同	quts
            去	quts
        """.trimIndent()
        val map = LinkedHashMap<String, MutableList<String>>()
        sampleDict.lineSequence().drop(5).forEach { line ->
            val parts = line.split('\t')
            map.getOrPut(parts[1]) { ArrayList() }.add(parts[0])
        }
        val idx = BriefPrefixIndex.buildFromDictLines(
            mainDictLines = sampleDict.lineSequence(),
            gt4GuardTwoPrefixes = setOf("ok", "os")
        )
        return map to idx
    }

    @Test
    fun `QA-OVERLAY-01 bimodal semicolon non-conflict`() {
        val (dictMap, briefIdx) = loadXiaoheDictAndIndex()
        val realDir = resolveXiaoheAssetDir()
        val trie = if (realDir != null && File(realDir, "quick_symbols.yaml").exists()) {
            SymbolTrie.parseQuickSymbolsYaml(File(realDir, "quick_symbols.yaml").readText(StandardCharsets.UTF_8))
        } else {
            SymbolTrie.defaultXiaohe()
        }
        // Ensure `an` has at least 2 candidates so `;` selects #2
        val augmented = HashMap(dictMap)
        if ((augmented["an"]?.size ?: 0) < 2) {
            augmented["an"] = listOf("安", "按")
        }
        val port = InMemoryTableRimePort(augmented)
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            overlayController = OverlaySymbolController(trie),
            reverseSplitEngine = ReverseSplitEngine(briefIdx)
        )

        // 1. Idle `;a` -> immediately commits `！` and returns to idle main schema
        assertTrue(coord.onCharKey(';') is CoordinatorKeyOutcome.Consumed)
        val outSemiA = coord.onCharKey('a')
        assertTrue(outSemiA is CoordinatorKeyOutcome.Commit)
        assertEquals(CommitAction.PlainText("！"), (outSemiA as CoordinatorKeyOutcome.Commit).action)
        assertFalse(coord.isComposing)

        // 2. Idle `;;` -> outputs `；` and exits
        coord.onCharKey(';')
        val outDoubleSemi = coord.onCharKey(';')
        assertEquals(CommitAction.PlainText("；"), (outDoubleSemi as CoordinatorKeyOutcome.Commit).action)
        assertFalse(coord.isComposing)

        // 3. Composing `an` + `;` -> selects 2nd candidate without entering overlay mode
        coord.onCharKey('a')
        coord.onCharKey('n')
        val secondExpected = coord.snapshotUi().candidates[1].commitText
        val semiSelect = coord.onCharKey(';')
        assertTrue(semiSelect is CoordinatorKeyOutcome.Commit)
        assertEquals(secondExpected, ((semiSelect as CoordinatorKeyOutcome.Commit).action as CommitAction.PlainText).text)
        assertFalse(coord.overlayController.isActive)
        assertFalse(coord.isComposing)

        // 4. `;mq` -> `①`, `;m` + Space -> `@`
        coord.onCharKey(';')
        assertTrue(coord.onCharKey('m') is CoordinatorKeyOutcome.Consumed)
        val mqOut = coord.onCharKey('q')
        assertEquals(CommitAction.PlainText("①"), (mqOut as CoordinatorKeyOutcome.Commit).action)
        assertFalse(coord.isComposing)

        coord.onCharKey(';')
        coord.onCharKey('m')
        val mSpaceOut = coord.onSpace()
        assertEquals(CommitAction.PlainText("@"), (mSpaceOut as CoordinatorKeyOutcome.Commit).action)
        assertFalse(coord.isComposing)
    }

    @Test
    fun `QA-OVERLAY-02 paired bracket action and atomic undo action`() {
        val (dictMap, briefIdx) = loadXiaoheDictAndIndex()
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = InMemoryTableRimePort(dictMap),
            overlayController = OverlaySymbolController(SymbolTrie.defaultXiaohe()),
            reverseSplitEngine = ReverseSplitEngine(briefIdx)
        )

        coord.onCharKey(';')
        val pairOut = coord.onCharKey('o') as CoordinatorKeyOutcome.Commit
        assertEquals(CommitAction.PairedText("「", "」"), pairOut.action)
        assertFalse(coord.isComposing)

        coord.onCharKey(';')
        val undoOut = coord.onCharKey('i') as CoordinatorKeyOutcome.Commit
        assertEquals(CommitAction.UndoLastCommit, undoOut.action)
        assertFalse(coord.isComposing)
    }

    @Test
    fun `QA-SPLIT-01 empty-code 2+2 reverse split and 5th-key push commit`() {
        val (dictMap, briefIdx) = loadXiaoheDictAndIndex()
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = InMemoryTableRimePort(dictMap),
            reverseSplitEngine = ReverseSplitEngine(briefIdx)
        )

        coord.onCharKey('h')
        coord.onCharKey('f')
        coord.onCharKey('k')
        val fourth = coord.onCharKey('n')
        assertTrue("hfkn has 2 split candidates so should stay composing", fourth is CoordinatorKeyOutcome.Consumed)
        val ui = (fourth as CoordinatorKeyOutcome.Consumed).ui
        assertEquals("hf'kn", ui.preedit)
        assertEquals("很可能", ui.candidates[0].displayText)
        assertEquals("很可能", ui.candidates[0].commitText)
        assertEquals("困难", ui.candidates[1].displayText)
        assertEquals("很困难", ui.candidates[1].commitText)

        // Pressing 5th key `a` pushes `很可能` and retains `a` as new composing head
        val fifth = coord.onCharKey('a')
        assertTrue(fifth is CoordinatorKeyOutcome.Commit)
        val commitFifth = fifth as CoordinatorKeyOutcome.Commit
        assertEquals(CommitAction.PlainText("很可能", fromRimeNative = false), commitFifth.action)
        assertEquals("a", commitFifth.ui.rawCode)
        assertTrue(commitFifth.ui.isComposing)
    }

    @Test
    fun `QA-SPLIT-02 93565 gt4-code ok and os prefix guard zero mis-split`() {
        val (dictMap, briefIdx) = loadXiaoheDictAndIndex()
        val splitEngine = ReverseSplitEngine(briefIdx)
        val profile = SchemaProfile.defaultXiaoheFlypy()

        // `okhg` has 0 exact 4-code matches, and `ok -> 👌`, `hg -> 很高` both exist in brief2,
        // yet `gt4PrefixSet` MUST block splitting so `okhguu -> 丁` remains reachable!
        val splitAt4 = splitEngine.tryReverseSplit(
            code = "okhg",
            rimeHasCandidates = false,
            profile = profile,
            singleCharMode = false,
            directCmdMatched = false
        )
        assertNull("okhg must NOT be mis-split into 👌很高", splitAt4)

        val coord = TypeFreeKeyCoordinator(
            profile = profile,
            rimePort = InMemoryTableRimePort(dictMap),
            reverseSplitEngine = splitEngine
        )
        for (c in "okhg") coord.onCharKey(c)
        assertFalse(coord.snapshotUi().isReverseSplitActive)
        coord.onCharKey('u')
        val sixth = coord.onCharKey('u')
        val hitDing = when (sixth) {
            is CoordinatorKeyOutcome.Commit -> (sixth.action as? CommitAction.PlainText)?.text == "丁"
            is CoordinatorKeyOutcome.Consumed -> sixth.ui.candidates.firstOrNull()?.commitText == "丁"
            else -> false
        }
        assertTrue("okhguu must hit candidate 丁", hitDing)
    }

    @Test
    fun `QA-ISOLATION-01 zero userdb pollution and 100 percent natural order preservation`() {
        val (dictMap, briefIdx) = loadXiaoheDictAndIndex()
        val port = InMemoryTableRimePort(dictMap)
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy(),
            rimePort = port,
            reverseSplitEngine = ReverseSplitEngine(briefIdx)
        )

        var autoLearnFeedCount = 0
        val baselineOrderAn = dictMap["an"].orEmpty().toList()

        repeat(50) { round ->
            // 1) Reverse split commit
            for (c in "hfkn") coord.onCharKey(c)
            val splitCommit = coord.onCharKey(';') as CoordinatorKeyOutcome.Commit // selects #2 很困难
            if (splitCommit.feedToAutoLearn) autoLearnFeedCount++

            // 2) Standard Xiaohe code commit
            coord.onCharKey('a')
            coord.onCharKey('n')
            val normalCommit = if (round % 2 == 0 && coord.snapshotUi().candidates.size >= 2) {
                coord.selectCandidate(1)
            } else {
                coord.onSpace()
            } as CoordinatorKeyOutcome.Commit
            if (normalCommit.feedToAutoLearn) autoLearnFeedCount++
        }

        assertEquals("librime userdb mutation bytes must be 0", 0L, port.userDictMutationBytes)
        assertEquals("Auto-Learn v2.0 feed queue count must be 0", 0, autoLearnFeedCount)

        coord.onCharKey('a')
        coord.onCharKey('n')
        val afterOrderAn = coord.snapshotUi().candidates.map { it.commitText }
        assertEquals("Candidate order must stay 100% locked to flypy.dict.yaml line order", baselineOrderAn, afterOrderAn)
    }

    @Test
    fun `QA-KEYTAO-01 Keytao 6 push-topup schema auto-detection and native 3-code push`() {
        val xkjd6SchemaYaml = """
            schema:
              schema_id: xkjd6
              name: 星空键道6
            engine:
              translators:
                - table_translator
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz"
              auto_select: true
              auto_select_pattern: "^[a-z][a-z][aiouv]+$|^[a-z]{4}$"
              algebra:
                - derive/^([bcdfghjklmnpqrstwxyz])([bcdfghjklmnpqrstwxyz])(.*)$/$2$1$3/
            translator:
              dictionary: xkjd6
              enable_sentence: false
              enable_user_dict: false
        """.trimIndent()

        val xkjd6DictYaml = """
            ---
            name: xkjd6
            version: "6.0"
            sort: original
            ...
            是	sb
            鼠标	sbb
            星	xka
            空	kl
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                zos.putNextEntry(ZipEntry("rime/xkjd6.schema.yaml"))
                zos.write(xkjd6SchemaYaml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("rime/xkjd6.dict.yaml"))
                zos.write(xkjd6DictYaml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val tempRoot = Files.createTempDirectory("qa_keytao_").toFile()
        try {
            val importer = SchemaPackageImporter(
                stagingParentDir = File(tempRoot, "staging"),
                targetSchemasRootDir = File(tempRoot, "schemas")
            )
            val imported = importer.importZip(ByteArrayInputStream(zipBytes))
            assertEquals(SchemaArchetype.PUSH_TOPUP_CODE, imported.profile.archetype)
            assertFalse(imported.profile.reverseSplitEnabled)
            assertFalse(imported.profile.fifthKeyTopCommit)
            assertFalse(imported.profile.allowAutoLearn)

            val keytaoMap = mapOf(
                "sb" to listOf("是"),
                "sbb" to listOf("鼠标"),
                "xka" to listOf("星"),
                "kl" to listOf("空")
            )
            val port = InMemoryTableRimePort(keytaoMap, pushTopupMode = true)
            val coord = TypeFreeKeyCoordinator(profile = imported.profile, rimePort = port)

            // 3-code single char `xka` (`a` in `[aiouv]`) followed by initial `k` -> native 3-code push commits `星`!
            coord.onCharKey('x')
            coord.onCharKey('k')
            coord.onCharKey('a')
            val pushStep = coord.onCharKey('k')
            assertTrue(pushStep is CoordinatorKeyOutcome.Commit)
            val commit = pushStep as CoordinatorKeyOutcome.Commit
            assertEquals(CommitAction.PlainText("星", fromRimeNative = true), commit.action)
            assertEquals("k", commit.ui.rawCode)
            coord.onCharKey('l')
            assertEquals("空", coord.snapshotUi().candidates.first().commitText)
            coord.reset()

            // `sb` / `sbb` briefs pass through with 0 loss
            coord.onCharKey('s')
            coord.onCharKey('b')
            assertEquals("是", coord.snapshotUi().candidates.first().commitText)
            coord.onCharKey('b')
            assertEquals("鼠标", coord.snapshotUi().candidates.first().commitText)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `QA-UTF8-EXTB-01 4-byte CJK Ext-B reverse lookup and codePointCount single-char mode`() {
        val extBFish = "\uD867\uDF7E" // 𩽾 U+29F7E (4-byte UTF-8: F0 A9 BD BE)
        val utf8Bytes = Utf8Safety.toStandardUtf8Bytes(extBFish)
        assertEquals(4, utf8Bytes.size)
        assertEquals(extBFish, Utf8Safety.fromStandardUtf8Bytes(utf8Bytes))
        assertEquals(1, Utf8Safety.countVisibleCharacters(extBFish))
        assertTrue(Utf8Safety.isSingleCharacter(extBFish))

        val realDir = resolveXiaoheAssetDir()
        val dzMap = if (realDir != null && File(realDir, "flypydz.dict.yaml").exists()) {
            DirectCommandEngine.loadReverseLookupFromDictLines(
                File(realDir, "flypydz.dict.yaml").readLines(StandardCharsets.UTF_8).asSequence()
            )
        } else {
            DirectCommandEngine.loadReverseLookupFromDictLines(
                sequenceOf("---", "name: flypydz", "...", "$extBFish\tanan", "鹤\thebn")
            )
        }

        val port = InMemoryTableRimePort(mapOf("anan" to listOf(extBFish, "安安")))
        val coord = TypeFreeKeyCoordinator(
            profile = SchemaProfile.defaultXiaoheFlypy().copy(autoCommitUniqueFull = false),
            rimePort = port,
            directCommandEngine = DirectCommandEngine(
                reverseLookupMap = dzMap,
                clipboardReader = { "${extBFish}康鱼" }
            )
        )

        // 1) `ofi` reverse lookup displays `[𩽾: anan]` with zero UTF-8 corruption
        coord.onCharKey('o')
        coord.onCharKey('f')
        val ofiOut = coord.onCharKey('i') as CoordinatorKeyOutcome.Consumed
        assertTrue(
            "Expected [𩽾: anan] in ${ofiOut.ui.candidates}",
            ofiOut.ui.candidates.first().displayText.contains("$extBFish: anan")
        )
        coord.reset()

        // 2) Single-character mode preserves `𩽾` (codePointCount == 1) while filtering 2-char `安安`
        coord.setSingleCharModeEnabled(true)
        for (c in "anan") coord.onCharKey(c)
        val cands = coord.snapshotUi().candidates
        assertEquals(1, cands.size)
        assertEquals(extBFish, cands[0].commitText)
        val commit = coord.onSpace() as CoordinatorKeyOutcome.Commit
        assertEquals(CommitAction.PlainText(extBFish, fromRimeNative = true), commit.action)
    }

    @Test
    fun `QA-IMPORTER-BIN-01 precompiled bin passthrough and 702-slot dynamic probing under 15ms`() {
        val (_, sourceBriefIdx) = loadXiaoheDictAndIndex()
        val fakeTableBinBytes = ByteArray(256).also { buf ->
            val header = "Rime::Table\u0000v1.17.0-flypy-binary-fixture".toByteArray(StandardCharsets.US_ASCII)
            System.arraycopy(header, 0, buf, 0, header.size)
            for (i in header.size until buf.size) buf[i] = (i * 31).toByte()
        }
        val expectedSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fakeTableBinBytes)
            .joinToString("") { "%02x".format(it) }

        val officialHangupSchema = """
            schema:
              schema_id: flypy
              name: 小鹤音形
            engine:
              segmentors:
                - affix_segmentor@ok_mode
                - abc_segmentor
              translators:
                - lua_translator@date_translator
                - lua_translator@time_translator
                - table_translator
                - table_translator@custom_phraseVD
                - table_translator@custom_phraseXT
            speller:
              alphabet: "abcdefghijklmnopqrstuvwxyz;'"
              max_code_length: 4
              auto_select: true
              auto_select_pattern: "^;.$|^\\w{4}$"
            translator:
              dictionary: flypy
              enable_sentence: false
              enable_user_dict: false
            custom_phraseVD:
              user_dict: flypy_top
              db_class: stabledb
            custom_phraseXT:
              user_dict: flypy_sys
              db_class: stabledb
            recognizer:
              patterns:
                ok_mode: "^o[ks][a-z']*$"
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos, StandardCharsets.UTF_8).use { zos ->
                fun addEntry(name: String, data: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
                addEntry("Squirrel/flypy.schema.yaml", officialHangupSchema.toByteArray(StandardCharsets.UTF_8))
                addEntry("Squirrel/build/flypy.table.bin", fakeTableBinBytes)
                addEntry("Squirrel/build/flypy.prism.bin", "Rime::Prism\u0000".toByteArray(StandardCharsets.US_ASCII))
                addEntry("Squirrel/build/flypy.reverse.bin", "Rime::Reverse\u0000".toByteArray(StandardCharsets.US_ASCII))
                addEntry("Squirrel/flypy_top.txt", "# coding: utf-8\n".toByteArray(StandardCharsets.UTF_8))
                addEntry("Squirrel/squirrel.yaml", "style:\n  color_scheme: native\n".toByteArray(StandardCharsets.UTF_8))
            }
            baos.toByteArray()
        }

        val tempRoot = Files.createTempDirectory("qa_bin_importer_").toFile()
        try {
            var compilerCalled = false
            val importer = SchemaPackageImporter(
                stagingParentDir = File(tempRoot, "staging"),
                targetSchemasRootDir = File(tempRoot, "schemas"),
                stagingCompiler = { _, _ ->
                    compilerCalled = true
                    true
                }
            )

            // Warm-up JVM JIT once so measured 702-slot probing reflects steady-state execution
            BriefPrefixIndex.buildFrom702SlotProbing(
                prober = { code, _ ->
                    if (code.length == 1) sourceBriefIdx.lookup1(code[0])
                    else sourceBriefIdx.lookup2(code[0], code[1])
                }
            )

            val result = importer.importZip(
                zipInputStream = ByteArrayInputStream(zipBytes),
                overrideProber = { code, _ ->
                    when (code.length) {
                        1 -> sourceBriefIdx.lookup1(code[0])
                        2 -> {
                            val real = sourceBriefIdx.lookup2(code[0], code[1])
                            if (real.isNotEmpty()) real
                            else listOf("词组$code") // ensures >=25% multi-char ratio when running on minimal CI fixture
                        }
                        else -> emptyList()
                    }
                }
            )

            // (a) squirrel.yaml & lua_translator stripped; build/flypy.table.bin preserved byte-for-byte
            assertEquals(ImportMode.PRECOMPILED_BIN, result.importMode)
            assertFalse("Plain dictionary compiler must NEVER run in PRECOMPILED_BIN mode", compilerCalled)
            assertTrue("squirrel.yaml" in result.strippedDesktopFiles)
            assertFalse(File(result.installedDir, "squirrel.yaml").exists())
            assertTrue(result.sanitizedLuaTranslators.isNotEmpty())
            assertEquals(expectedSha256, result.precompiledTableSha256)
            assertEquals(
                expectedSha256,
                SchemaPackageImporter.sha256Hex(File(result.installedDir, "build/flypy.table.bin"))
            )
            assertTrue("Missing flypy_sys.txt placeholder should be generated", File(result.installedDir, "flypy_sys.txt").exists())

            // (b) 702-slot dynamic probing completed within 15ms and identified FIXED_YINXING
            assertTrue("Expected 702-slot probe < 15ms, actual=${result.probeDurationMs}ms", result.probeDurationMs < 15L)
            assertEquals(SchemaArchetype.FIXED_YINXING, result.profile.archetype)
            assertTrue(result.profile.reverseSplitEnabled)
            assertNotNull(result.briefIndexSnapshotFile)
            assertTrue(result.briefIndexSnapshotFile!!.exists())

            // (c) Reverse split `hfkn -> 很可能` & `okhg` >4-code guard active
            val splitEngine = ReverseSplitEngine(result.briefIndex)
            val hfkn = splitEngine.tryReverseSplit("hfkn", rimeHasCandidates = false, profile = result.profile)
            assertNotNull(hfkn)
            assertEquals("很可能", hfkn!!.candidates[0].commitText)
            val okhg = splitEngine.tryReverseSplit("okhg", rimeHasCandidates = false, profile = result.profile)
            assertNull("okhg must be guarded by affix ok* guard in PRECOMPILED_BIN mode", okhg)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
