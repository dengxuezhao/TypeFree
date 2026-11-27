package com.typefree.ime.v301

import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.bubble.TypeFreeAccessibilityService
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.HistoryRetention
import com.typefree.ime.data.InMemoryKeyValueStore
import com.typefree.ime.data.InputScheme
import com.typefree.ime.data.JvmAesGcmCipher
import com.typefree.ime.data.LlmConfig
import com.typefree.ime.data.MultimodalConfig
import com.typefree.ime.data.ProviderCredential
import com.typefree.ime.data.SecretVault
import com.typefree.ime.data.SttConfig
import com.typefree.ime.ime.ImeLogic
import com.typefree.ime.ime.ImeUiState
import com.typefree.ime.ime.engine.RimeDataManager
import com.typefree.ime.ime.nextCycledInputScheme
import com.typefree.ime.ime.predict.AiSentenceCompleter
import com.typefree.ime.ime.predict.PredictionCoordinator
import com.typefree.ime.ime.predict.PredictionStore
import com.typefree.ime.ime.predict.SentenceCompletionExecutor
import com.typefree.ime.network.AiPipelineEngine
import com.typefree.rime.RimeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V301BugfixAndSecurityTest {

    // =========================================================================
    // M1: Multimodal Vision Pre-Check, HTTP 400 Short-Circuit & Prompt Stripping
    // =========================================================================

    @Test
    fun m1_knownPureTextModels_coverAllBuiltInPureTextPresetsAndAllowVisionModels() {
        val pureTextModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-ai/DeepSeek-V3",
            "qwen-turbo",
            "qwen-turbo-latest",
            "qwen-plus",
            "qwen-max",
            "qwen2.5-72b-instruct",
            "qwen3-235b-a22b",
            "qwen/qwen3-32b",
            "doubao-1-5-lite-32k",
            "doubao-1-5-pro-32k",
            "glm-4-flash",
            "yi-lightning",
            "llama-3.3-70b-instruct"
        )
        for (model in pureTextModels) {
            assertTrue(
                "Expected '$model' to be recognized as a pure-text model",
                AiPipelineEngine.isKnownPureTextModel(model)
            )
        }

        val visionCapableModels = listOf(
            "gemini-2.5-flash",
            "gpt-4o",
            "gpt-4o-mini",
            "qwen-vl-max",
            "qwen2.5-vl-72b-instruct",
            "qwen2.5-omni-7b",
            "doubao-1-5-vision-pro-32k",
            "doubao-seed-2.0-lite",
            "glm-4v-flash"
        )
        for (model in visionCapableModels) {
            assertFalse(
                "Expected '$model' NOT to be marked as pure-text",
                AiPipelineEngine.isKnownPureTextModel(model)
            )
        }
    }

    @Test
    fun m1_canRouteVisionScreenshot_blocksPureTextWithoutGeminiFallback() {
        assertFalse(
            AiPipelineEngine.canRouteVisionScreenshot(
                primaryBaseUrl = "https://api.deepseek.com/v1",
                primaryModel = "deepseek-chat",
                fallbackGeminiConfig = null
            )
        )
        assertTrue(
            AiPipelineEngine.canRouteVisionScreenshot(
                primaryBaseUrl = "https://api.deepseek.com/v1",
                primaryModel = "deepseek-chat",
                fallbackGeminiConfig = MultimodalConfig(
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    apiKey = "AIzaSyTestKey",
                    model = "gemini-2.5-flash"
                )
            )
        )
    }

    // =========================================================================
    // M2: C++ JNI Standard UTF-8 <-> UTF-16 Surrogate Pair Conversion
    // =========================================================================

    @Test
    fun m2_rimeJniSource_usesStandardUtf8ToUtf16SurrogateConversionWithoutNewStringUtf() {
        val candidates = listOf(
            File("rime/src/main/cpp/rime_jni.cpp"),
            File("../rime/src/main/cpp/rime_jni.cpp")
        )
        val jniFile = candidates.firstOrNull { it.exists() }
        assertNotNull("rime_jni.cpp must exist in workspace", jniFile)
        val source = jniFile!!.readText(Charsets.UTF_8)

        assertTrue("Must use env->NewString(utf16.data(), ...)", source.contains("env->NewString("))
        assertTrue("Must use env->GetStringRegion(...)", source.contains("env->GetStringRegion("))
        assertTrue("Must encode high surrogate 0xD800", source.contains("0xD800"))
        assertTrue("Must encode low surrogate 0xDC00", source.contains("0xDC00"))
        assertTrue("Must fallback to U+FFFD on malformed sequence", source.contains("0xFFFD"))
        assertFalse("Must NOT call NewStringUTF on standard UTF-8", source.contains("NewStringUTF"))
        assertFalse("Must NOT call GetStringUTFChars", source.contains("GetStringUTFChars"))
    }

    // =========================================================================
    // M3: Android Keystore SecretVault & Backup Rules Isolation
    // =========================================================================

    @Test
    fun m3_secretVault_encryptsAllApiKeysAndStripsPlaintextFromPublicSettingsJson() {
        val store = InMemoryKeyValueStore()
        val vault = SecretVault(store = store, cipher = JvmAesGcmCipher())
        val rawSettings = AppSettings(
            sttConfig = SttConfig(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "sk-dashscope-secret-111111",
                model = "qwen3-asr-flash"
            ),
            llmConfig = LlmConfig(
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-deepseek-secret-222222",
                model = "deepseek-chat"
            ),
            multimodalConfig = MultimodalConfig(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                apiKey = "AIzaSyGeminiSecret333333",
                model = "gemini-2.5-flash"
            ),
            providerVault = mapOf(
                "google_gemini" to ProviderCredential(
                    providerId = "google_gemini",
                    apiKey = "AIzaSyGeminiSecret333333"
                ),
                "volcengine" to ProviderCredential(
                    providerId = "volcengine",
                    apiKey = "ark-secret-444444",
                    appId = "appid-123",
                    sttApiKey = "sauc-secret-555555"
                )
            )
        )

        assertTrue(SecretVault.containsPlaintextSecrets(rawSettings))
        vault.saveSecretsFromSettings(rawSettings)

        val stripped = vault.stripSecretsForPublicPersistence(rawSettings)
        assertFalse(SecretVault.containsPlaintextSecrets(stripped))

        val publicJson = Gson().toJson(stripped)
        assertFalse(publicJson.contains("sk-dashscope-secret-111111"))
        assertFalse(publicJson.contains("sk-deepseek-secret-222222"))
        assertFalse(publicJson.contains("AIzaSyGeminiSecret333333"))
        assertFalse(publicJson.contains("ark-secret-444444"))
        assertFalse(publicJson.contains("sauc-secret-555555"))
        // Non-secret appId remains preserved
        assertTrue(publicJson.contains("appid-123"))

        // Verify encrypted values in store are ciphertext (`v1:...`), never plaintext
        for (k in listOf(SecretVault.KEY_STT_API_KEY, SecretVault.KEY_LLM_API_KEY, SecretVault.KEY_MM_API_KEY)) {
            val rawStored = store.getString(k)
            assertNotNull(rawStored)
            assertTrue(rawStored!!.startsWith("v1:"))
            assertFalse(rawStored.contains("secret"))
        }

        val hydrated = vault.hydrateSettingsWithSecrets(stripped)
        assertEquals("sk-dashscope-secret-111111", hydrated.sttConfig.apiKey)
        assertEquals("sk-deepseek-secret-222222", hydrated.llmConfig.apiKey)
        assertEquals("AIzaSyGeminiSecret333333", hydrated.multimodalConfig.apiKey)
        assertEquals("AIzaSyGeminiSecret333333", hydrated.providerVault["google_gemini"]?.apiKey)
        assertEquals("ark-secret-444444", hydrated.providerVault["volcengine"]?.apiKey)
        assertEquals("sauc-secret-555555", hydrated.providerVault["volcengine"]?.sttApiKey)
        assertEquals("appid-123", hydrated.providerVault["volcengine"]?.appId)
    }

    @Test
    fun m3_backupAndDataExtractionRules_excludeSensitivePrefsAndVoiceHistory() {
        fun resolveAppFile(rel: String): File {
            return listOf(File("app/$rel"), File(rel)).first { it.exists() }
        }
        val backupXml = resolveAppFile("src/main/res/xml/backup_rules.xml").readText()
        val extractionXml = resolveAppFile("src/main/res/xml/data_extraction_rules.xml").readText()
        val manifestXml = resolveAppFile("src/main/AndroidManifest.xml").readText()

        assertTrue(backupXml.contains("typefree_secure_secrets.xml"))
        assertTrue(backupXml.contains("typefree_ime_prefs.xml"))
        assertTrue(backupXml.contains("voice_history"))
        assertTrue(extractionXml.contains("typefree_secure_secrets.xml"))
        assertTrue(extractionXml.contains("typefree_ime_prefs.xml"))
        assertTrue(extractionXml.contains("voice_history"))
        assertTrue(manifestXml.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifestXml.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    // =========================================================================
    // M4: Accessibility Service High-Frequency Event Throttling & No getWindows()
    // =========================================================================

    @Test
    fun m4_accessibilityContentChanged_throttlesWithin180ms_skipsWhenBubbleDisabled_andDisablesMultiWindowScan() {
        // 1. When bubbleEnabled == false, TYPE_WINDOW_CONTENT_CHANGED is completely skipped
        val (disabledShouldHandle, disabledMultiWin) = TypeFreeAccessibilityService.shouldProcessAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            bubbleEnabled = false,
            nowMs = 1000L,
            lastContentChangedMs = 0L
        )
        assertFalse(disabledShouldHandle)
        assertFalse(disabledMultiWin)

        // 2. When bubbleEnabled == true, first event at 1000ms is processed WITHOUT multi-window scan
        val (firstShouldHandle, firstMultiWin) = TypeFreeAccessibilityService.shouldProcessAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            bubbleEnabled = true,
            nowMs = 1000L,
            lastContentChangedMs = 0L
        )
        assertTrue(firstShouldHandle)
        assertFalse("TYPE_WINDOW_CONTENT_CHANGED must never call getWindows()", firstMultiWin)

        // 3. Burst event 80ms later (< 180ms) is throttled
        val (burstShouldHandle, _) = TypeFreeAccessibilityService.shouldProcessAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            bubbleEnabled = true,
            nowMs = 1080L,
            lastContentChangedMs = 1000L
        )
        assertFalse(burstShouldHandle)

        // 4. Event 185ms later (>= 180ms) is allowed
        val (afterWindowShouldHandle, afterWindowMultiWin) = TypeFreeAccessibilityService.shouldProcessAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            bubbleEnabled = true,
            nowMs = 1185L,
            lastContentChangedMs = 1000L
        )
        assertTrue(afterWindowShouldHandle)
        assertFalse(afterWindowMultiWin)

        // 5. Window state change is always handled and allows multi-window scan
        val (stateChangedShouldHandle, stateChangedMultiWin) = TypeFreeAccessibilityService.shouldProcessAccessibilityEvent(
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            bubbleEnabled = false,
            nowMs = 1090L,
            lastContentChangedMs = 1000L
        )
        assertTrue(stateChangedShouldHandle)
        assertTrue(stateChangedMultiWin)
    }

    // =========================================================================
    // M6: Voice History Isolated Archiver & Manual Incognito Mode Alignment
    // =========================================================================

    @Test
    fun m6_voiceHistoryArchiver_createsIsolatedFilesPerRecording_andManualIncognitoPropagates() {
        val tempRoot = Files.createTempDirectory("typefree_m6_test").toFile()
        try {
            val sharedLastWav = File(tempRoot, "last_typefree_voice.wav")
            sharedLastWav.writeBytes("WAV_ONE_PAYLOAD".toByteArray())
            val archived1 = WavAudioRecorder.archiveWavForHistory(
                baseDir = tempRoot,
                wavFile = sharedLastWav,
                retention = HistoryRetention.FOREVER,
                nowMs = 1700000001000L
            )

            // Overwrite shared last_typefree_voice.wav with second recording
            sharedLastWav.writeBytes("WAV_TWO_DIFFERENT_PAYLOAD".toByteArray())
            val archived2 = WavAudioRecorder.archiveWavForHistory(
                baseDir = tempRoot,
                wavFile = sharedLastWav,
                retention = HistoryRetention.FOREVER,
                nowMs = 1700000002000L
            )

            assertNotEquals(archived1.absolutePath, archived2.absolutePath)
            assertEquals("WAV_ONE_PAYLOAD", archived1.readText())
            assertEquals("WAV_TWO_DIFFERENT_PAYLOAD", archived2.readText())

            // Manual incognito mode flag activates ImeLogic.isIncognitoImeOptions even on normal input fields
            assertFalse(ImeLogic.isIncognitoImeOptions(imeOptions = 0, inputType = 0, manualIncognitoMode = false))
            assertTrue(ImeLogic.isIncognitoImeOptions(imeOptions = 0, inputType = 0, manualIncognitoMode = true))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // M7: L3 AI Completion Idle Timer Renewal + Rime Custom Cycle & Lua/OpenCC
    // =========================================================================

    @Test
    fun m7_predictionCoordinator_renewsIdleTimerWhenAsyncL3SuggestionArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stateFlow = MutableStateFlow(ImeUiState())
            // idleDismissMs = 220ms; L3 completion arrives at ~130ms (10ms debounce + 120ms network).
            // Without timer renewal on L3 arrival, idle timer would fire at 220ms (only 90ms after L3 arrival).
            // With timer renewal on L3 arrival, the 220ms timer restarts at 130ms -> survives past 260ms and dismisses after ~350ms.
            val completer = AiSentenceCompleter(
                scope = scope,
                debounceMs = 10L,
                executor = SentenceCompletionExecutor { _, _, _, _ ->
                    delay(120L)
                    Result.success("顺祝商祺")
                }
            )
            val coordinator = PredictionCoordinator(
                scope = scope,
                stateFlow = stateFlow,
                settingsProvider = {
                    AppSettings(
                        predictionEnabled = true,
                        aiCompletionEnabled = true,
                        llmConfig = LlmConfig(
                            baseUrl = "https://api.deepseek.com/v1",
                            apiKey = "sk-test",
                            model = "deepseek-chat"
                        )
                    )
                },
                predictionStore = PredictionStore.inMemory(),
                aiSentenceCompleter = completer,
                idleDismissMs = 220L
            )

            coordinator.onWordCommitted("感谢您的支持")
            delay(160L)
            assertEquals("顺祝商祺", stateFlow.value.aiCompletionSuggestion)

            // At t = 260ms (past the initial 220ms window, but within the renewed 130+220=350ms window),
            // suggestion must STILL be visible!
            delay(100L)
            assertEquals("顺祝商祺", stateFlow.value.aiCompletionSuggestion)

            // Wait another 160ms (t = 420ms > 350ms) -> renewed idle timer fires and dismisses predictions
            delay(160L)
            assertNull(stateFlow.value.aiCompletionSuggestion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun m7_rimeCustomSchemeCycle_andZipImportExtractsLuaAndOpenccAndCleansUpOnRestore() {
        // 1. Cycle order without custom schema (standard build omits built-in FLYPY_TABLE)
        assertEquals(
            InputScheme.RIME_PINYIN,
            nextCycledInputScheme(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, hasCustomSchema = false, includeBuiltinFlypyTable = false)
        )
        // 2. Cycle order with custom schema imported
        assertEquals(
            InputScheme.RIME_CUSTOM,
            nextCycledInputScheme(InputScheme.RIME_DOUBLE_PINYIN_FLYPY, hasCustomSchema = true, includeBuiltinFlypyTable = false)
        )
        assertEquals(
            InputScheme.RIME_PINYIN,
            nextCycledInputScheme(InputScheme.RIME_CUSTOM, hasCustomSchema = true, includeBuiltinFlypyTable = false)
        )

        // 3. Zip import with rime.lua, lua/*.lua, opencc/*, and restoreDefaultRimeDataSync cleanup
        val tempRoot = Files.createTempDirectory("typefree_m7_rime").toFile()
        try {
            val sharedDir = File(tempRoot, "shared")
            val userDir = File(tempRoot, "user")
            val manager = RimeDataManager(
                sharedDir = sharedDir,
                userDir = userDir,
                rimeBridge = RimeBridge()
            )

            val zipBytes = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zos ->
                    fun putEntry(name: String, content: String) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(content.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    putEntry(
                        "wanxiang.schema.yaml",
                        "schema:\n  schema_id: wanxiang\n  name: 万象拼音\n"
                    )
                    putEntry("rime.lua", "-- root rime.lua\n")
                    putEntry("lua/date_translator.lua", "return function() end\n")
                    putEntry("opencc/emoji.json", "{\"name\": \"emoji\"}\n")
                    putEntry("opencc/emoji.txt", "开心\t😄 😊\n")
                    putEntry("cn_dicts/custom_ext.dict.yaml", "---\nname: custom_ext\n...\n测试\tce shi\n")
                    // Path traversal entry must be ignored safely
                    putEntry("../evil.lua", "-- should never extract\n")
                }
            }.toByteArray()

            manager.importRimeSchemaZipSync(ByteArrayInputStream(zipBytes))

            assertTrue(manager.hasCustomSchemaImported())
            assertEquals("万象拼音", manager.readCustomSchemaName())
            assertTrue(File(sharedDir, "rime.lua").exists())
            assertTrue(File(sharedDir, "lua/date_translator.lua").exists())
            assertTrue(File(sharedDir, "opencc/emoji.json").exists())
            assertTrue(File(sharedDir, "opencc/emoji.txt").exists())
            assertTrue(File(sharedDir, "cn_dicts/custom_ext.dict.yaml").exists())
            assertFalse(File(tempRoot, "evil.lua").exists())

            manager.restoreDefaultRimeDataSync()
            assertFalse(manager.hasCustomSchemaImported())
            assertFalse(File(sharedDir, "rime.lua").exists())
            assertFalse(File(sharedDir, "lua").exists())
            assertFalse(File(sharedDir, "opencc/emoji.json").exists())
            assertFalse(File(sharedDir, "opencc/emoji.txt").exists())
            assertTrue(File(sharedDir, "opencc/s2tw.json").exists())
            assertFalse(File(sharedDir, "cn_dicts/custom_ext.dict.yaml").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
