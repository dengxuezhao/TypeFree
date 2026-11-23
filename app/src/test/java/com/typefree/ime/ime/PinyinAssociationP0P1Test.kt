package com.typefree.ime.ime

import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.engine.RimeDeploymentState
import com.typefree.ime.ime.predict.LexiconPrefixIndex
import com.typefree.ime.ime.predict.PredictionCoordinator
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.StaticPredictor
import com.typefree.rime.RimeContextSnapshot
import com.typefree.rime.RimeSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

class PinyinAssociationP0P1Test {

    private class StubRimeClient : RimeSessionClient {
        override var activeSchemaId: String = "rime_ice"
        override var pageSize: Int = 24
        override val isNativeLoaded: Boolean = false
        override var isDeployed: Boolean = false
        override var wordCount: Int = 26_000
        override fun initialize(sharedDir: String, userDir: String): Boolean = true
        override fun deploy(schemaId: String): Boolean {
            if (schemaId.isNotBlank()) activeSchemaId = schemaId
            isDeployed = true
            return true
        }
        override fun selectSchema(schemaId: String): Boolean {
            activeSchemaId = schemaId
            return true
        }
        override fun processKey(keyCode: Int, mask: Int): Boolean = false
        override fun processKeyText(keys: String): RimeContextSnapshot = RimeContextSnapshot()
        override fun getContext(maxCandidates: Int): RimeContextSnapshot = RimeContextSnapshot()
        override fun selectCandidate(index: Int): String? = null
        override fun commitComposition(): String? = null
        override fun getCommit(): String? = null
        override fun clearComposition() {}
        override fun setOption(option: String, value: Boolean) {}
        override fun getOption(option: String): Boolean = false
        override fun syncUserData(): Boolean = true
        override fun getVersion(): String = "1.17.0-stub"
    }

    @Test
    fun lexiconPrefixIndex_buildsWeightedSuffixesFromRimeDicts() {
        val sampleDict = """
            # sample dict
            ---
            name: base
            version: "1.0"
            ...
            人工智能	ren gong zhi neng	50000
            人工成本	ren gong cheng ben	12000
            人工服务	ren gong fu wu	8000
            不好意思	bu hao yi si	90000
            不好看	bu hao kan	4000
            注意事项	zhu yi shi xiang	60000
            注意安全	zhu yi an quan	45000
            测试	ce shi	5
        """.trimIndent()

        val index = LexiconPrefixIndex()
        val entries = index.rebuildFromStreams(listOf(ByteArrayInputStream(sampleDict.toByteArray(Charsets.UTF_8))))
        assertTrue(entries >= 7)

        assertEquals(listOf("智能", "成本", "服务"), index.querySuffixes("人工", limit = 4))
        assertEquals(listOf("意思", "看"), index.querySuffixes("不好", limit = 4))
        assertEquals(listOf("事项", "安全"), index.querySuffixes("注意", limit = 4))
    }

    @Test
    fun staticPredictor_loadsBundledCompactBigramsAndSkipsBinaryPredictDb() {
        val bundledFile = listOf(
            File("src/main/assets/predict/zh_bigrams_compact.tsv"),
            File("app/src/main/assets/predict/zh_bigrams_compact.tsv")
        ).firstOrNull { it.isFile }
        assertNotNull("Expected bundled zh_bigrams_compact.tsv asset", bundledFile)

        val predictor = StaticPredictor()
        val loadedCount = FileInputStream(bundledFile!!).use { predictor.loadBundledFromTsvStream(it) }
        assertTrue("Expected >5000 bundled headwords, got $loadedCount", loadedCount > 5_000)

        assertEquals("谢谢", predictor.predictWords("好的", limit = 5).first())
        assertTrue(predictor.predictWords("人工", limit = 5).contains("智能"))
        assertTrue(predictor.predictWords("不好", limit = 5).contains("意思"))
        assertTrue(predictor.predictWords("代码", limit = 5).contains("已经"))

        // Verify binary predict.db in sharedDir is ignored while predict.tsv is loaded
        val tempDir = Files.createTempDirectory("typefree_predict_dir_").toFile()
        try {
            File(tempDir, "predict.db").writeBytes(byteArrayOf(0x00, 0x7F, 0x01, 0x02, 0x09, 0x55))
            File(tempDir, "predict.tsv").writeText("定制词\t优先项\t次选项\n", Charsets.UTF_8)
            val customCount = predictor.loadFromRimeSharedDir(tempDir)
            assertEquals(1, customCount)
            assertEquals("优先项", predictor.predictWords("定制词", limit = 3).first())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun predictionStore_supportsRecencyDecayAndTrigramContext() {
        val store = PredictionStore.inMemory()
        val dayMs = 86_400_000L
        val baseNow = 1_750_000_000_000L
        val oldTime = baseNow - 90L * dayMs // 3 half-lives ago -> 3 * 0.125 = 0.375

        // Old word recorded 3 times 90 days ago
        store.recordBigram("晚上", "加班", nowMs = oldTime)
        store.recordBigram("晚上", "加班", nowMs = oldTime + 1_000L)
        store.recordBigram("晚上", "加班", nowMs = oldTime + 2_000L)

        // Fresh word recorded 2 times today -> decayed score = 2.0 > 0.375
        store.recordBigram("晚上", "跑步", nowMs = baseNow - 60_000L)
        store.recordBigram("晚上", "跑步", nowMs = baseNow)

        val ranked = store.queryNextWords("晚上", limit = 4, nowMs = baseNow)
        assertEquals(listOf("跑步", "加班"), ranked)

        // Trigram context beats generic bigram when prevPrevWord matches
        assertTrue(store.recordTrigram("周末", "晚上", "看电影", nowMs = baseNow))
        assertEquals(1, store.getTrigramCount("周末", "晚上", "看电影"))

        val contextual = store.queryNextWordsWithContext(
            prevPrevWord = "周末",
            prevWord = "晚上",
            limit = 4,
            nowMs = baseNow
        )
        assertEquals(listOf("看电影", "跑步", "加班"), contextual)

        // Incognito blocks trigram writes
        store.isIncognitoMode = true
        assertFalse(store.recordTrigram("周末", "晚上", "秘密词", nowMs = baseNow))
        assertEquals(0, store.getTrigramCount("周末", "晚上", "秘密词"))
    }

    @Test
    fun predictionCoordinator_recordsTrigramAndResetsContextOnPunctuation() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = PredictionStore.inMemory()
            val stateFlow = MutableStateFlow(ImeUiState(tabMode = KeyboardTabMode.QWERTY, isFlypyChineseMode = true))
            val coordinator = PredictionCoordinator(
                scope = scope,
                stateFlow = stateFlow,
                predictionStore = store,
                staticPredictor = StaticPredictor()
            )

            coordinator.onWordCommitted("今天")
            coordinator.onWordCommitted("晚上")
            coordinator.onWordCommitted("打球")

            assertEquals("晚上", coordinator.secondLastCommittedWord)
            assertEquals("打球", coordinator.lastCommittedWord)
            assertEquals(1, store.getTrigramCount("今天", "晚上", "打球"))

            // Punctuation clears both last and second-last context words
            coordinator.onPunctuationCommitted()
            assertNull(coordinator.lastCommittedWord)
            assertNull(coordinator.secondLastCommittedWord)

            // Re-entering "今天" -> "晚上" should surface "打球" at #1 via trigram
            coordinator.onWordCommitted("今天")
            coordinator.onWordCommitted("晚上")
            assertEquals("打球", stateFlow.value.predictionCandidates.first().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun rimeDataManager_managesDynamicDictImportTablesAndOctagramPatches() {
        val tempRoot = Files.createTempDirectory("typefree_p0_test_").toFile()
        try {
            val sharedDir = File(tempRoot, "rime/shared")
            val userDir = File(tempRoot, "rime/user")
            val manager = RimeDataManager(sharedDir, userDir, StubRimeClient(), appContext = null)

            val initial = manager.deployDefaultSync()
            assertTrue(initial is RimeDeploymentState.Ready)
            assertNull(manager.installedGrammarModelId())
            assertFalse(File(sharedDir, "rime_ice.custom.yaml").exists())

            // Simulate ext + others + tencent sub-dictionaries present -> rime_ice.dict.yaml updates dynamically
            val cnDicts = File(sharedDir, "cn_dicts").apply { mkdirs() }
            File(cnDicts, "ext.dict.yaml").writeText("---\nname: ext\n...\n测试\tce shi\t10\n", Charsets.UTF_8)
            File(cnDicts, "others.dict.yaml").writeText("---\nname: others\n...\n测试\tce shi\t10\n", Charsets.UTF_8)
            File(cnDicts, "tencent.dict.yaml").writeText("---\nname: tencent\n...\n测试\tce shi\t10\n", Charsets.UTF_8)
            manager.syncRimeIceDictYaml()

            val dictYaml = File(sharedDir, "rime_ice.dict.yaml").readText(Charsets.UTF_8)
            assertTrue(dictYaml.contains("cn_dicts/8105"))
            assertTrue(dictYaml.contains("cn_dicts/base"))
            assertTrue(dictYaml.contains("cn_dicts/ext"))
            assertTrue(dictYaml.contains("cn_dicts/tencent"))
            assertTrue(dictYaml.contains("cn_dicts/others"))

            // Install octagram model A -> writes grammar patches for rime_ice, double_pinyin_flypy, t9
            val fakeGramA = ByteArray(1024) { 0x42 }
            val readyA = manager.installGrammarModelFromStreamSync(
                modelId = "zh-hans-t-essay-bgc-compact",
                inputStream = ByteArrayInputStream(fakeGramA),
                expectedBytes = fakeGramA.size.toLong()
            )
            assertTrue(readyA is RimeDeploymentState.Ready)
            assertEquals("zh-hans-t-essay-bgc-compact", (readyA as RimeDeploymentState.Ready).activeGrammarModelId)
            assertTrue(File(sharedDir, "zh-hans-t-essay-bgc-compact.gram").isFile)

            for (schema in listOf("rime_ice", "double_pinyin_flypy", "t9")) {
                val patchFile = File(sharedDir, "$schema.custom.yaml")
                assertTrue("Expected $schema.custom.yaml", patchFile.isFile)
                val patchContent = patchFile.readText(Charsets.UTF_8)
                assertTrue(patchContent.contains("language: zh-hans-t-essay-bgc-compact"))
                assertTrue(patchContent.contains("translator/contextual_suggestions: true"))
            }

            // Switch to model B -> old .gram A is removed automatically
            val fakeGramB = ByteArray(2048) { 0x24 }
            val readyB = manager.installGrammarModelFromStreamSync(
                modelId = "wanxiang-lts-zh-hans",
                inputStream = ByteArrayInputStream(fakeGramB),
                expectedBytes = fakeGramB.size.toLong()
            )
            assertEquals("wanxiang-lts-zh-hans", (readyB as RimeDeploymentState.Ready).activeGrammarModelId)
            assertFalse(File(sharedDir, "zh-hans-t-essay-bgc-compact.gram").exists())
            assertTrue(File(sharedDir, "wanxiang-lts-zh-hans.gram").isFile)
            assertTrue(File(sharedDir, "double_pinyin_flypy.custom.yaml").readText(Charsets.UTF_8).contains("language: wanxiang-lts-zh-hans"))

            // Uninstall grammar model -> removes .gram and all grammar custom patches
            val afterRemove = manager.removeGrammarModelSync()
            assertNull((afterRemove as RimeDeploymentState.Ready).activeGrammarModelId)
            assertNull(manager.installedGrammarModelId())
            assertFalse(File(sharedDir, "wanxiang-lts-zh-hans.gram").exists())
            assertFalse(File(sharedDir, "rime_ice.custom.yaml").exists())
            assertFalse(File(sharedDir, "double_pinyin_flypy.custom.yaml").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
