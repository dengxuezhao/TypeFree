package com.typefree.ime.data

import android.content.Context
import android.content.pm.PackageManager
import android.text.InputType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.typefree.core.offline.OfflineMode
import com.typefree.core.offline.PendingOfflineRestore
import com.typefree.ime.data.stats.DailyStatsRecord
import com.typefree.ime.data.stats.DailyStatsStore
import com.typefree.ime.data.stats.InMemoryDailyStatsBackend
import com.typefree.ime.data.stats.InsightsAggregator
import com.typefree.ime.data.stats.InsightsSnapshot
import com.typefree.ime.data.stats.PredictionBackupArchive
import com.typefree.ime.data.stats.SqliteDailyStatsBackend
import com.typefree.ime.data.stats.StatsTimeRange
import com.typefree.ime.data.stats.TokenUsageSnapshot
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.ime.predict.PredictionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class AppRepository(
    private val appContext: Context?,
    internal val publicStore: KeyValueStore,
    internal val secretVault: SecretVault
) {

    constructor(appContext: Context) : this(
        appContext = appContext,
        publicStore = SharedPreferencesKeyValueStore(
            appContext.getSharedPreferences(PUBLIC_PREFS_NAME, Context.MODE_PRIVATE)
        ),
        secretVault = SecretVault.fromContext(appContext)
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _settings = MutableStateFlow(loadSettingsInternal())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(
        pruneHistoryByRetention(loadHistoryInternal(), _settings.value.historyRetention)
    )
    val history: StateFlow<List<HistoryRecord>> = _history.asStateFlow()

    val dailyStatsStore: DailyStatsStore = DailyStatsStore(
        backend = if (appContext != null) {
            SqliteDailyStatsBackend(appContext)
        } else {
            InMemoryDailyStatsBackend()
        }
    ).also { store ->
        store.isStatsEnabled = _settings.value.inputStatsEnabled
        store.isIncognitoMode = _settings.value.manualIncognitoMode
        val alreadyBackfilled = publicStore.getBoolean(KEY_DAILY_STATS_BACKFILLED, false)
        if (!alreadyBackfilled && _history.value.isNotEmpty()) {
            store.backfillFromHistoryIfEmpty(_history.value)
            publicStore.putBoolean(KEY_DAILY_STATS_BACKFILLED, true)
        }
    }

    val dailyStatsRevision: StateFlow<Int> get() = dailyStatsStore.revision

    internal val fallbackPredictionStore: PredictionStore = PredictionStore.inMemory()

    private val _clipboardItems = MutableStateFlow(loadClipboardInternal())
    val clipboardItems: StateFlow<List<ClipboardEntry>> = _clipboardItems.asStateFlow()

    private val _isImeWindowVisible = MutableStateFlow(false)
    val isImeWindowVisible: StateFlow<Boolean> = _isImeWindowVisible.asStateFlow()

    fun setImeWindowVisible(visible: Boolean) {
        _isImeWindowVisible.value = visible
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadSettingsInternal(): AppSettings {
        val raw = publicStore.getString(KEY_SETTINGS, null)
        if (raw == null) {
            publicStore.putBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, true)
            if (com.typefree.ime.BuildConfig.IS_XIAOHE_FLAVOR) {
                publicStore.putBoolean(KEY_MIGRATED_V360_XIAOHE_SCHEME, true)
            }
            val firstRunSettings = if (appContext != null) {
                val sysLocale = appContext.resources?.configuration?.locales?.let { list ->
                    if (!list.isEmpty) list[0] else null
                } ?: Locale.getDefault()
                resolveFirstRunSettings(systemLocale = sysLocale)
            } else {
                AppSettings()
            }
            return secretVault.hydrateSettingsWithSecrets(firstRunSettings)
        }
        return try {
            val parsed = gson.fromJson(raw, AppSettings::class.java) ?: AppSettings()
            val safeTargets = (parsed.translationTargets ?: emptyList())
                .filter { it.isNotBlank() }
                .ifEmpty { DefaultPresets.defaultTranslationTargets() }
            val safeActiveTarget = if (parsed.activeTranslateTarget.isNullOrBlank()) {
                safeTargets.first()
            } else {
                parsed.activeTranslateTarget
            }
            val safeDict = (parsed.dictionary ?: DefaultPresets.defaultDictionary()).map { item ->
                item.copy(
                    id = item.id.ifBlank { UUID.randomUUID().toString() },
                    wrongVariants = item.wrongVariants ?: "",
                    note = item.note ?: "",
                    source = item.source ?: DictionarySource.MANUAL,
                    createdAt = if (item.createdAt <= 0L) System.currentTimeMillis() else item.createdAt
                )
            }
            val resolvedFlypyEnabled = if (raw.contains("\"flypyEnabled\"")) {
                parsed.flypyEnabled
            } else {
                true
            }
            val resolvedCommaPeriodQuickSelect = if (raw.contains("\"flypyCommaPeriodQuickSelect\"")) {
                parsed.flypyCommaPeriodQuickSelect
            } else {
                true
            }
            val resolvedRememberLastKeyboard = if (raw.contains("\"rememberLastKeyboardMode\"")) {
                parsed.rememberLastKeyboardMode
            } else {
                true
            }
            val resolvedLastKeyboardTab = (parsed.lastKeyboardTab ?: "").trim().ifEmpty { "VOICE" }
            val resolvedLastQwertyIsChinese = if (raw.contains("\"lastQwertyIsChinese\"")) {
                parsed.lastQwertyIsChinese
            } else {
                resolvedFlypyEnabled
            }
            val migratedV340CompactLift = publicStore.getBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, false)
            val resolvedBackspaceSwipeUpClear = if (raw.contains("\"backspaceSwipeUpClearEnabled\"")) {
                parsed.backspaceSwipeUpClearEnabled
            } else {
                false
            }
            val rawQwertyShowNumberRow = if (raw.contains("\"qwertyShowNumberRow\"")) {
                parsed.qwertyShowNumberRow
            } else {
                false
            }
            val resolvedQwertyShowNumberRow = if (!migratedV340CompactLift) {
                false
            } else {
                rawQwertyShowNumberRow
            }
            val resolvedQwertyKeyPopupPreview = if (raw.contains("\"qwertyKeyPopupPreview\"")) {
                parsed.qwertyKeyPopupPreview
            } else {
                true
            }
            val resolvedClipboardEnabled = if (raw.contains("\"clipboardEnabled\"")) {
                parsed.clipboardEnabled
            } else {
                true
            }
            val resolvedClipboardQuickSuggestion = if (raw.contains("\"clipboardQuickPasteSuggestion\"")) {
                parsed.clipboardQuickPasteSuggestion
            } else {
                true
            }
            val resolvedPauseMedia = if (raw.contains("\"pauseMediaWhileRecording\"")) {
                parsed.pauseMediaWhileRecording
            } else {
                true
            }
            val resolvedWhisperMode = if (raw.contains("\"whisperModeEnabled\"")) {
                parsed.whisperModeEnabled
            } else {
                false
            }
            val resolvedEqualAskAi = if (raw.contains("\"equalEqualAskAiEnabled\"")) {
                parsed.equalEqualAskAiEnabled
            } else {
                true
            }
            val resolvedKeyboardHeightLevel = if (raw.contains("\"keyboardHeightLevel\"")) {
                parsed.keyboardHeightLevel.coerceIn(-2, 2)
            } else {
                0
            }
            val rawKeyboardHeightMode: KeyboardHeightMode =
                if (raw.contains("\"keyboardHeightMode\"")) {
                    @Suppress("USELESS_ELVIS")
                    parsed.keyboardHeightMode ?: KeyboardHeightMode.LIFT
                } else if (!migratedV340CompactLift) {
                    KeyboardHeightMode.STRETCH
                } else {
                    KeyboardHeightMode.LIFT
                }
            val rawKeyboardLiftLevel = if (raw.contains("\"keyboardLiftLevel\"")) {
                parsed.keyboardLiftLevel.coerceIn(0, 4)
            } else {
                0
            }
            val resolvedKeyboardHeightMode: KeyboardHeightMode = if (!migratedV340CompactLift) {
                KeyboardHeightMode.LIFT
            } else {
                rawKeyboardHeightMode
            }
            val resolvedKeyboardLiftLevel = if (!migratedV340CompactLift) {
                migrateStretchLevelToLiftLevel(
                    oldMode = rawKeyboardHeightMode,
                    oldHeightLevel = resolvedKeyboardHeightLevel,
                    oldLiftLevel = rawKeyboardLiftLevel
                )
            } else {
                rawKeyboardLiftLevel
            }
            val migratedV30LiveWrite = publicStore.getBoolean(KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT, false)
            val resolvedLiveWriteToEditor = if (!migratedV30LiveWrite) {
                publicStore.putBoolean(KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT, true)
                true
            } else if (raw.contains("\"liveWriteToEditor\"")) {
                parsed.liveWriteToEditor
            } else {
                true
            }
            val migratedXiaoheScheme = !com.typefree.ime.BuildConfig.IS_XIAOHE_FLAVOR ||
                publicStore.getBoolean(KEY_MIGRATED_V360_XIAOHE_SCHEME, false)
            // 新用户默认 Rime 全拼 (真实 librime 1.17.0)；老用户升级（存在旧 JSON 但无 inputScheme 字段）保持 FLYPY_TABLE
            val rawInputScheme = if (raw.contains("\"inputScheme\"")) {
                parsed.inputScheme ?: InputScheme.RIME_PINYIN
            } else {
                InputScheme.FLYPY_TABLE
            }
            val resolvedInputScheme = resolveUpgradedInputScheme(
                rawInputScheme = rawInputScheme,
                isXiaoheFlavor = com.typefree.ime.BuildConfig.IS_XIAOHE_FLAVOR,
                alreadyMigratedXiaoheScheme = migratedXiaoheScheme
            )
            val resolvedFixedCandidateOrder = if (raw.contains("\"fixedCandidateOrder\"")) {
                parsed.fixedCandidateOrder
            } else {
                null
            }
            val resolvedEnglishPrefixVetoEnabled = if (raw.contains("\"englishPrefixVetoEnabled\"")) {
                parsed.englishPrefixVetoEnabled
            } else {
                false
            }
            val resolvedCodetableAutoEncodeEnabled = if (raw.contains("\"codetableAutoEncodeEnabled\"")) {
                parsed.codetableAutoEncodeEnabled
            } else {
                false
            }
            val resolvedCodetableFiveKeyBottomRow = if (raw.contains("\"codetableFiveKeyBottomRow\"")) {
                parsed.codetableFiveKeyBottomRow
            } else {
                false
            }
            val resolvedXiaoheDirectCommandsEnabled = if (raw.contains("\"xiaoheDirectCommandsEnabled\"")) {
                parsed.xiaoheDirectCommandsEnabled
            } else {
                true
            }
            val resolvedXiaoheReverseSplitEnabled = if (raw.contains("\"xiaoheReverseSplitEnabled\"")) {
                parsed.xiaoheReverseSplitEnabled
            } else {
                true
            }
            val resolvedXiaoheNaturalContinuationEnabled = if (raw.contains("\"xiaoheNaturalContinuationEnabled\"")) {
                parsed.xiaoheNaturalContinuationEnabled
            } else {
                true
            }
            val resolvedXiaoheNaturalContinuationLimit = if (raw.contains("\"xiaoheNaturalContinuationLimit\"")) {
                parsed.xiaoheNaturalContinuationLimit.coerceIn(1, MAX_NATURAL_CONTINUATION_LIMIT)
            } else {
                DEFAULT_NATURAL_CONTINUATION_LIMIT
            }
            val resolvedPassiveCalculatorEnabled = if (raw.contains("\"passiveCalculatorEnabled\"")) {
                parsed.passiveCalculatorEnabled
            } else {
                true
            }
            val resolvedEnglishModeWordSuggestionEnabled = if (raw.contains("\"englishModeWordSuggestionEnabled\"")) {
                parsed.englishModeWordSuggestionEnabled
            } else {
                true
            }
            val resolvedEnglishAutoCap = if (raw.contains("\"englishAutoCap\"")) {
                parsed.englishAutoCap
            } else {
                true
            }
            val resolvedEnglishAutoCorrect = if (raw.contains("\"englishAutoCorrect\"")) {
                parsed.englishAutoCorrect
            } else {
                true
            }
            val resolvedAppLanguage = if (raw.contains("\"appLanguage\"")) {
                AppStrings.normalizeAppLanguage(parsed.appLanguage)
            } else {
                AppStrings.LANG_SYSTEM
            }
            val resolvedPhraseSuggestionL2Enabled = if (raw.contains("\"phraseSuggestionL2Enabled\"")) {
                parsed.phraseSuggestionL2Enabled
            } else {
                true
            }
            val resolvedCustomKeyGesturesJson = (parsed.customKeyGesturesJson ?: "").trim()
            val resolvedPredictionEnabled = if (raw.contains("\"predictionEnabled\"")) {
                parsed.predictionEnabled
            } else {
                true
            }
            val resolvedAiCompletionEnabled = if (raw.contains("\"aiCompletionEnabled\"")) {
                parsed.aiCompletionEnabled
            } else {
                false
            }
            val resolvedManualIncognitoMode = if (raw.contains("\"manualIncognitoMode\"")) {
                parsed.manualIncognitoMode
            } else {
                false
            }
            val resolvedInputStatsEnabled = if (raw.contains("\"inputStatsEnabled\"")) {
                parsed.inputStatsEnabled
            } else {
                true
            }
            val safeReplyAttitudes = (parsed.replyAttitudePresets ?: emptyList())
                .mapNotNull { item ->
                    val lbl = (item.label ?: "").trim()
                    val inst = (item.instruction ?: "").trim()
                    if (lbl.isEmpty() || inst.isEmpty()) {
                        null
                    } else {
                        ReplyAttitudePreset(
                            id = (item.id ?: "").trim().ifEmpty { UUID.randomUUID().toString() },
                            label = lbl,
                            instruction = inst
                        )
                    }
                }
                .ifEmpty { DefaultPresets.defaultReplyAttitudes() }
            val safeSnippets = (parsed.snippets ?: DefaultPresets.defaultSnippets()).map { s ->
                s.copy(
                    id = s.id.ifBlank { UUID.randomUUID().toString() },
                    title = s.title ?: "",
                    voiceTriggers = s.voiceTriggers ?: "",
                    flypyCode = SnippetMatcher.normalizeFlypyCode(s.flypyCode ?: ""),
                    content = s.content ?: ""
                )
            }
            val rawVault = parsed.providerVault ?: emptyMap()
            val safeVault = rawVault.mapValues { (k, v) ->
                ProviderCredential(
                    providerId = (v.providerId ?: "").ifBlank { k },
                    apiKey = v.apiKey ?: "",
                    sttApiKey = v.sttApiKey ?: "",
                    appId = v.appId ?: "",
                    customBaseUrl = v.customBaseUrl ?: ""
                )
            }
            val rawStt = parsed.sttConfig ?: SttConfig()
            val rawSttBaseUrl = rawStt.baseUrl ?: "https://api.siliconflow.cn/v1"
            val rawSttPresetId = rawStt.presetId ?: "siliconflow"
            val isDeepSeekStt = rawSttPresetId == "deepseek_asr" ||
                rawSttPresetId == "deepseek" ||
                com.typefree.ime.network.EndpointResolver.isDeepSeekAsrEndpoint(rawSttBaseUrl)
            val safeStt = rawStt.copy(
                presetId = if (isDeepSeekStt && rawSttPresetId.isBlank()) "deepseek_asr" else rawSttPresetId,
                protocol = rawStt.protocol ?: SttProtocol.OPENAI_HTTP,
                baseUrl = if (isDeepSeekStt) {
                    com.typefree.ime.network.EndpointResolver.resolveSttEndpoint(
                        rawSttBaseUrl.ifBlank { com.typefree.ime.network.EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL }
                    )
                } else {
                    rawSttBaseUrl
                },
                apiKey = rawStt.apiKey ?: "",
                appId = rawStt.appId ?: "",
                resourceId = (rawStt.resourceId ?: "").ifBlank { "volc.seedasr.sauc.duration" },
                model = (rawStt.model ?: "").ifBlank {
                    if (isDeepSeekStt) "deepseek-asr" else "FunAudioLLM/SenseVoiceSmall"
                },
                language = rawStt.language ?: ""
            )
            val rawLlm = parsed.llmConfig ?: LlmConfig()
            val safeLlm = rawLlm.copy(
                presetId = rawLlm.presetId ?: "deepseek",
                baseUrl = rawLlm.baseUrl ?: "https://api.deepseek.com/v1",
                apiKey = rawLlm.apiKey ?: "",
                model = rawLlm.model ?: "deepseek-chat",
                thinkingMode = rawLlm.thinkingMode ?: ThinkingMode.DISABLED
            )
            val rawMm = parsed.multimodalConfig ?: MultimodalConfig()
            val safeMm = rawMm.copy(
                presetId = rawMm.presetId ?: "gemini",
                protocol = rawMm.protocol ?: MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = rawMm.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta",
                apiKey = rawMm.apiKey ?: "",
                model = rawMm.model ?: "gemini-3.5-flash-lite",
                thinkingMode = rawMm.thinkingMode ?: ThinkingMode.DISABLED
            )
            val rawOffline = parsed.offlineConfig
            val safeOfflineConfig = if (rawOffline == null) {
                OfflineModelConfig()
            } else {
                val hasOfflineObj = raw.contains("\"offlineConfig\"")
                rawOffline.copy(
                    mode = rawOffline.mode ?: OfflineMode.OFF,
                    asrSlotOn = if (hasOfflineObj && raw.contains("\"asrSlotOn\"")) rawOffline.asrSlotOn else true,
                    slmSlotOn = if (hasOfflineObj && raw.contains("\"slmSlotOn\"")) rawOffline.slmSlotOn else true,
                    selectedAsrModelId = rawOffline.selectedAsrModelId?.trim()?.takeIf { it.isNotEmpty() },
                    selectedSlmModelId = rawOffline.selectedSlmModelId?.trim()?.takeIf { it.isNotEmpty() },
                    schemaVersion = rawOffline.schemaVersion.coerceAtLeast(1)
                )
            }
            val rawPendingRestore = parsed.pendingOfflineRestore
            val safePendingOfflineRestore = if (rawPendingRestore == null) {
                null
            } else {
                val safeMode = rawPendingRestore.mode ?: OfflineMode.OFF
                val safeAsr = rawPendingRestore.selectedAsrModelId?.trim()?.takeIf { it.isNotEmpty() }
                val safeSlm = rawPendingRestore.selectedSlmModelId?.trim()?.takeIf { it.isNotEmpty() }
                if (safeMode == OfflineMode.OFF && safeAsr == null && safeSlm == null) {
                    null
                } else {
                    PendingOfflineRestore(
                        mode = safeMode,
                        selectedAsrModelId = safeAsr,
                        selectedSlmModelId = safeSlm
                    )
                }
            }
            val preliminary = parsed.copy(
                sttConfig = safeStt,
                llmConfig = safeLlm,
                multimodalConfig = safeMm,
                offlineConfig = safeOfflineConfig,
                pendingOfflineRestore = safePendingOfflineRestore,
                themePreference = parsed.themePreference ?: ThemePreference.SYSTEM,
                appLanguage = resolvedAppLanguage,
                flypyEnabled = resolvedFlypyEnabled,
                flypyCommaPeriodQuickSelect = resolvedCommaPeriodQuickSelect,
                rememberLastKeyboardMode = resolvedRememberLastKeyboard,
                lastKeyboardTab = resolvedLastKeyboardTab,
                lastQwertyIsChinese = resolvedLastQwertyIsChinese,
                qwertyShowNumberRow = resolvedQwertyShowNumberRow,
                qwertyKeyPopupPreview = resolvedQwertyKeyPopupPreview,
                backspaceSwipeUpClearEnabled = resolvedBackspaceSwipeUpClear,
                clipboardEnabled = resolvedClipboardEnabled,
                clipboardQuickPasteSuggestion = resolvedClipboardQuickSuggestion,
                pauseMediaWhileRecording = resolvedPauseMedia,
                whisperModeEnabled = resolvedWhisperMode,
                equalEqualAskAiEnabled = resolvedEqualAskAi,
                keyboardHeightLevel = resolvedKeyboardHeightLevel,
                keyboardHeightMode = resolvedKeyboardHeightMode,
                keyboardLiftLevel = resolvedKeyboardLiftLevel,
                liveWriteToEditor = resolvedLiveWriteToEditor,
                inputScheme = resolvedInputScheme,
                fixedCandidateOrder = resolvedFixedCandidateOrder,
                englishPrefixVetoEnabled = resolvedEnglishPrefixVetoEnabled,
                codetableAutoEncodeEnabled = resolvedCodetableAutoEncodeEnabled,
                codetableFiveKeyBottomRow = resolvedCodetableFiveKeyBottomRow,
                xiaoheDirectCommandsEnabled = resolvedXiaoheDirectCommandsEnabled,
                xiaoheReverseSplitEnabled = resolvedXiaoheReverseSplitEnabled,
                xiaoheNaturalContinuationEnabled = resolvedXiaoheNaturalContinuationEnabled,
                xiaoheNaturalContinuationLimit = resolvedXiaoheNaturalContinuationLimit,
                passiveCalculatorEnabled = resolvedPassiveCalculatorEnabled,
                englishModeWordSuggestionEnabled = resolvedEnglishModeWordSuggestionEnabled,
                englishAutoCap = resolvedEnglishAutoCap,
                englishAutoCorrect = resolvedEnglishAutoCorrect,
                phraseSuggestionL2Enabled = resolvedPhraseSuggestionL2Enabled,
                customKeyGesturesJson = resolvedCustomKeyGesturesJson,
                keycapBadgeMode = parsed.keycapBadgeMode ?: KeycapBadgeMode.SWIPE_UP,
                predictionEnabled = resolvedPredictionEnabled,
                aiCompletionEnabled = resolvedAiCompletionEnabled,
                manualIncognitoMode = resolvedManualIncognitoMode,
                inputStatsEnabled = resolvedInputStatsEnabled,
                onboardingCompleted = if (raw.contains("\"onboardingCompleted\"")) {
                    parsed.onboardingCompleted
                } else {
                    true
                },
                replyAttitudePresets = safeReplyAttitudes,
                providerVault = safeVault,
                snippets = safeSnippets,
                historyRetention = parsed.historyRetention ?: HistoryRetention.FOREVER,
                translationTargets = safeTargets,
                activeTranslateTarget = safeActiveTarget,
                bubbleYRatio = if (parsed.bubbleYRatio in 0.08f..0.90f) parsed.bubbleYRatio else 0.48f,
                styles = (parsed.styles ?: emptyList()).ifEmpty { DefaultPresets.defaultStyles() },
                appRules = parsed.appRules ?: DefaultPresets.defaultAppRules(),
                dictionary = safeDict
            )
            val hydrated = secretVault.hydrateSettingsWithSecrets(preliminary)
            if (SecretVault.containsPlaintextSecrets(preliminary) || !migratedV340CompactLift || !migratedXiaoheScheme) {
                secretVault.saveSecretsFromSettings(hydrated)
                val sanitized = secretVault.stripSecretsForPublicPersistence(hydrated)
                publicStore.putString(KEY_SETTINGS, gson.toJson(sanitized))
                if (!migratedV340CompactLift) {
                    publicStore.putBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, true)
                }
                if (!migratedXiaoheScheme) {
                    publicStore.putBoolean(KEY_MIGRATED_V360_XIAOHE_SCHEME, true)
                }
            }
            hydrated
        } catch (_: Exception) {
            secretVault.hydrateSettingsWithSecrets(AppSettings())
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadHistoryInternal(): List<HistoryRecord> {
        val raw = publicStore.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryRecord>>() {}.type
            val list = gson.fromJson<List<HistoryRecord>>(raw, type) ?: emptyList()
            list.map { item ->
                val resolvedIntent = item.intentMode ?: if (item.isSpeakToEdit) {
                    VoiceIntentMode.SPEAK_TO_EDIT
                } else {
                    VoiceIntentMode.DICTATE
                }
                item.copy(
                    intentMode = resolvedIntent,
                    translateTargetLanguage = item.translateTargetLanguage ?: "",
                    audioFilePath = item.audioFilePath ?: "",
                    executionSource = item.executionSource ?: ""
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadClipboardInternal(): List<ClipboardEntry> {
        val raw = publicStore.getString(KEY_CLIPBOARD, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ClipboardEntry>>() {}.type
            val list = gson.fromJson<List<ClipboardEntry>>(raw, type) ?: emptyList()
            sortClipboardEntries(
                list.mapNotNull { item ->
                    val clean = (item.text ?: "").trim()
                    if (clean.isEmpty()) null
                    else item.copy(
                        id = item.id.ifBlank { UUID.randomUUID().toString() },
                        text = clean,
                        timestamp = if (item.timestamp <= 0L) System.currentTimeMillis() else item.timestamp
                    )
                }
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveClipboardList(updated: List<ClipboardEntry>) {
        val sorted = sortClipboardEntries(updated)
        _clipboardItems.value = sorted
        publicStore.putString(KEY_CLIPBOARD, gson.toJson(sorted))
    }

    fun recordClipboardText(
        rawText: String,
        pinned: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): ClipboardEntry? {
        if (!_settings.value.clipboardEnabled && !pinned) return null
        val (updated, entry) = upsertClipboardList(
            existing = _clipboardItems.value,
            rawText = rawText,
            forcePinned = pinned,
            nowMs = nowMs,
            maxUnpinned = MAX_CLIPBOARD_UNPINNED
        )
        if (entry != null) {
            saveClipboardList(updated)
        }
        return entry
    }

    fun togglePinClipboardItem(id: String) {
        val curr = _clipboardItems.value
        if (curr.none { it.id == id }) return
        val updated = curr.map { item ->
            if (item.id == id) item.copy(isPinned = !item.isPinned) else item
        }
        saveClipboardList(updated)
    }

    fun deleteClipboardItem(id: String) {
        val curr = _clipboardItems.value
        val updated = curr.filterNot { it.id == id }
        if (updated.size != curr.size) {
            saveClipboardList(updated)
        }
    }

    fun clearUnpinnedClipboardItems() {
        val curr = _clipboardItems.value
        val pinnedOnly = curr.filter { it.isPinned }
        if (pinnedOnly.size != curr.size) {
            saveClipboardList(pinnedOnly)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val prevRetention = _settings.value.historyRetention
        val updated = transform(_settings.value)
        _settings.value = updated
        dailyStatsStore.isStatsEnabled = updated.inputStatsEnabled
        dailyStatsStore.isIncognitoMode = updated.manualIncognitoMode
        secretVault.saveSecretsFromSettings(updated)
        val sanitized = secretVault.stripSecretsForPublicPersistence(updated)
        publicStore.putString(KEY_SETTINGS, gson.toJson(sanitized))

        if (updated.historyRetention != prevRetention) {
            val pruned = pruneHistoryByRetention(_history.value, updated.historyRetention)
            if (pruned.size != _history.value.size) {
                _history.value = pruned
                publicStore.putString(KEY_HISTORY, gson.toJson(pruned))
            }
        }
    }

    fun setInputStatsEnabled(enabled: Boolean) {
        dailyStatsStore.isStatsEnabled = enabled
        updateSettings { it.copy(inputStatsEnabled = enabled) }
    }

    fun resetDailyStats() {
        publicStore.putBoolean(KEY_DAILY_STATS_BACKFILLED, true)
        dailyStatsStore.resetAll()
    }

    fun computeInsightsSnapshot(
        range: StatsTimeRange,
        nowMs: Long = System.currentTimeMillis()
    ): InsightsSnapshot {
        val settingsSnap = _settings.value
        val historySnap = _history.value
        val dailyRecords = dailyStatsStore.listAllRecords()
        val (bigramCount, trigramCount, topChains) = appContext?.let { ctx ->
            PredictionStore.readTopWordChainsSummary(ctx, limit = 8)
        } ?: Triple(
            fallbackPredictionStore.totalBigramCount(),
            fallbackPredictionStore.totalTrigramCount(),
            fallbackPredictionStore.listTopBigrams(8).map { "${it.prevWord} → ${it.nextWord}" }
        )
        return InsightsAggregator.buildSnapshot(
            range = range,
            dailyRecords = dailyRecords,
            history = historySnap,
            settings = settingsSnap,
            personalBigramCount = bigramCount,
            personalTrigramCount = trigramCount,
            topWordChains = topChains,
            dbSizeBytes = dailyStatsStore.dbSizeBytes(),
            nowMs = nowMs
        )
    }

    fun computeTokenUsageSnapshot(
        range: StatsTimeRange,
        nowMs: Long = System.currentTimeMillis()
    ): TokenUsageSnapshot {
        val dailyRecords = dailyStatsStore.listAllRecords()
        return InsightsAggregator.buildTokenUsageSnapshot(
            range = range,
            dailyRecords = dailyRecords,
            nowMs = nowMs
        )
    }

    /**
     * Losslessly merges imported [HistoryRecord] items into local history without duplicating existing
     * entries (deduped by `id` and `(timestamp, polishedText)`), stripping cross-device `audioFilePath`.
     * Returns the number of newly added unique history records.
     */
    fun mergeImportedHistory(imported: List<HistoryRecord>): Int {
        if (imported.isEmpty()) return 0
        val existing = _history.value
        val existingIds = HashSet<String>(existing.size * 2)
        val existingSignatures = HashSet<Pair<Long, String>>(existing.size * 2)
        for (item in existing) {
            if (item.id.isNotBlank()) existingIds.add(item.id)
            existingSignatures.add(item.timestamp to item.polishedText.trim())
        }

        val added = ArrayList<HistoryRecord>()
        for (raw in imported) {
            val cleanPolished = raw.polishedText.trim()
            if (cleanPolished.isEmpty()) continue
            val cleanId = raw.id.ifBlank { UUID.randomUUID().toString() }
            val sig = raw.timestamp to cleanPolished
            if (cleanId in existingIds || sig in existingSignatures) continue
            existingIds.add(cleanId)
            existingSignatures.add(sig)
            added.add(
                raw.copy(
                    id = cleanId,
                    polishedText = cleanPolished,
                    audioFilePath = "",
                    translateTargetLanguage = raw.translateTargetLanguage,
                    executionSource = raw.executionSource
                )
            )
        }
        if (added.isEmpty()) return 0

        val retention = _settings.value.historyRetention
        val effectiveRetention = if (retention == HistoryRetention.NEVER) HistoryRetention.FOREVER else retention
        val merged = pruneHistoryByRetention(
            (existing + added).sortedByDescending { it.timestamp },
            effectiveRetention
        ).take(MAX_HISTORY_ITEMS)
        _history.value = merged
        publicStore.putString(KEY_HISTORY, gson.toJson(merged))
        return added.size
    }

    fun mergeImportedDailyStats(records: List<DailyStatsRecord>): Int {
        if (records.isEmpty()) return 0
        publicStore.putBoolean(KEY_DAILY_STATS_BACKFILLED, true)
        return dailyStatsStore.mergeImportedRecords(records)
    }

    /**
     * Losslessly merges imported pinned clipboard items into local clipboard history,
     * preserving all existing entries and avoiding duplicates by trimmed text.
     * Returns the number of newly added or newly pinned items.
     */
    fun mergeImportedPinnedClipboard(items: List<ClipboardEntry>): Int {
        if (items.isEmpty()) return 0
        val current = _clipboardItems.value.toMutableList()
        var mergedCount = 0
        for (raw in items) {
            val cleanText = raw.text.trim()
            if (cleanText.isEmpty()) continue
            val capped = if (cleanText.length > MAX_CLIP_CHARS) cleanText.take(MAX_CLIP_CHARS) else cleanText
            val safeTs = if (raw.timestamp > 0L) raw.timestamp else System.currentTimeMillis()
            val idx = current.indexOfFirst { it.text == capped }
            if (idx >= 0) {
                val prev = current[idx]
                if (!prev.isPinned || safeTs > prev.timestamp) {
                    current[idx] = prev.copy(
                        isPinned = true,
                        timestamp = maxOf(prev.timestamp, safeTs)
                    )
                    if (!prev.isPinned) mergedCount++
                }
            } else {
                current.add(
                    ClipboardEntry(
                        id = raw.id.ifBlank { UUID.randomUUID().toString() },
                        text = capped,
                        timestamp = safeTs,
                        isPinned = true
                    )
                )
                mergedCount++
            }
        }
        if (mergedCount > 0 || current.size != _clipboardItems.value.size) {
            saveClipboardList(current)
        }
        return mergedCount
    }

    fun exportPredictionsArchive(): PredictionBackupArchive {
        return appContext?.let { ctx ->
            PredictionStore.exportBackupArchive(ctx)
        } ?: fallbackPredictionStore.exportArchive()
    }

    fun importAndMergePredictions(archive: PredictionBackupArchive): Pair<Int, Int> {
        if (archive.isEmpty) return 0 to 0
        return appContext?.let { ctx ->
            PredictionStore.importAndMergeArchive(ctx, archive)
        } ?: fallbackPredictionStore.mergeArchive(archive)
    }

    fun updateOfflineConfig(transform: (OfflineModelConfig) -> OfflineModelConfig) {
        updateSettings { curr ->
            curr.copy(offlineConfig = transform(curr.offlineConfig))
        }
    }

    fun clearPendingOfflineRestore() {
        if (_settings.value.pendingOfflineRestore == null) return
        updateSettings { curr ->
            curr.copy(pendingOfflineRestore = null)
        }
    }

    fun addHistory(record: HistoryRecord) {
        val currSettings = _settings.value
        if (currSettings.inputStatsEnabled && !currSettings.manualIncognitoMode) {
            dailyStatsStore.recordVoiceAndAi(record)
        }
        val retention = currSettings.historyRetention
        if (retention == HistoryRetention.NEVER) {
            return
        }
        val prunedExisting = pruneHistoryByRetention(_history.value, retention)
        val updated = (listOf(record) + prunedExisting).take(MAX_HISTORY_ITEMS)
        _history.value = updated
        publicStore.putString(KEY_HISTORY, gson.toJson(updated))
    }

    fun deleteHistoryItem(id: String) {
        val updated = _history.value.filterNot { it.id == id }
        _history.value = updated
        publicStore.putString(KEY_HISTORY, gson.toJson(updated))
    }

    fun clearHistory(alsoResetStats: Boolean = false) {
        _history.value = emptyList()
        publicStore.remove(KEY_HISTORY)
        if (alsoResetStats) {
            resetDailyStats()
        }
    }

    fun deleteDictionaryEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        updateSettings { curr ->
            curr.copy(dictionary = curr.dictionary.filterNot { it.id in ids })
        }
    }

    /**
     * Auto-learns a corrected word/phrase into the user's Personal Dictionary (`AUTO_LEARNED`)
     * when the user manually edits freshly dictated text on the built-in keyboard.
     */
    fun learnCorrectionFromDiff(beforeText: String, afterText: String): DictionaryEntry? {
        if (!_settings.value.autoLearnDictionary) return null
        val pair = extractAutoLearnedReplacement(beforeText, afterText) ?: return null
        val (wrongPart, correctedTerm) = pair

        var learnedEntry: DictionaryEntry? = null
        updateSettings { curr ->
            val existingIdx = curr.dictionary.indexOfFirst {
                it.term.equals(correctedTerm, ignoreCase = true)
            }
            val updatedDict = if (existingIdx >= 0) {
                val existing = curr.dictionary[existingIdx]
                val existingVariants = existing.wrongVariants
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val mergedVariants = if (
                    wrongPart.isNotBlank() &&
                    !wrongPart.equals(correctedTerm, ignoreCase = true) &&
                    existingVariants.none { it.equals(wrongPart, ignoreCase = true) }
                ) {
                    (existingVariants + wrongPart).joinToString(", ")
                } else {
                    existing.wrongVariants
                }
                val merged = existing.copy(wrongVariants = mergedVariants, enabled = true)
                learnedEntry = merged
                curr.dictionary.toMutableList().apply { set(existingIdx, merged) }
            } else {
                val created = DictionaryEntry(
                    term = correctedTerm,
                    wrongVariants = if (!wrongPart.equals(correctedTerm, ignoreCase = true)) wrongPart else "",
                    note = "打字改错自动学习",
                    enabled = true,
                    source = DictionarySource.AUTO_LEARNED
                )
                learnedEntry = created
                listOf(created) + curr.dictionary
            }
            curr.copy(dictionary = updatedDict)
        }
        return learnedEntry
    }

    /**
     * Resolve human-readable app label from package name.
     */
    fun resolveAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "未知应用"
        _settings.value.appRules.firstOrNull { it.packageName == packageName }?.let {
            if (it.appName.isNotBlank()) return it.appName
        }
        val ctx = appContext ?: return packageName.substringAfterLast('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } catch (_: Exception) {
            packageName
        }
    }

    /**
     * Resolve which ToneStyle should be active right now based on user manual override
     * or App-Aware automatic context detection.
     */
    fun resolveActiveStyle(packageName: String?, inputType: Int = 0): Pair<ToneStyle, Boolean> {
        val current = _settings.value
        val styles = current.styles.ifEmpty { DefaultPresets.defaultStyles() }
        val defaultStyle = styles.firstOrNull { it.id == "smart_default" } ?: styles.first()

        if (current.selectedManualStyleId != "auto") {
            val manual = styles.firstOrNull { it.id == current.selectedManualStyleId } ?: defaultStyle
            return manual to false
        }

        if (!current.appAwareToneEnabled || packageName.isNullOrBlank()) {
            return defaultStyle to true
        }

        val matchedRule = current.appRules.firstOrNull {
            it.enabled && it.packageName.equals(packageName, ignoreCase = true)
        }
        if (matchedRule != null) {
            val style = styles.firstOrNull { it.id == matchedRule.styleId }
            if (style != null) return style to true
        }

        val pkgLower = packageName.lowercase()
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val heuristicStyleId = when {
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
                pkgLower.contains("mail") || pkgLower.contains("docs") || pkgLower.contains("note") -> "formal_doc"
            pkgLower.contains("chat") || pkgLower.contains("messenger") || pkgLower.contains("weibo") -> "casual_chat"
            pkgLower.contains("work") || pkgLower.contains("lark") || pkgLower.contains("ding") || pkgLower.contains("teams") -> "work_collab"
            pkgLower.contains("code") || pkgLower.contains("term") || pkgLower.contains("git") -> "code_prompt"
            else -> "smart_default"
        }
        val heuristicStyle = styles.firstOrNull { it.id == heuristicStyleId } ?: defaultStyle
        return heuristicStyle to true
    }

    companion object {
        const val PUBLIC_PREFS_NAME = "typefree_ime_prefs"
        internal const val KEY_SETTINGS = "settings_v1"
        internal const val KEY_HISTORY = "history_v1"
        internal const val KEY_CLIPBOARD = "clipboard_v1"
        private const val KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT = "migrated_v30_live_write_default"
        internal const val KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT = "mig_v340_compact_4row_and_lift"
        internal const val KEY_MIGRATED_V360_XIAOHE_SCHEME = "mig_v360_xiaohe_scheme"
        internal const val KEY_DAILY_STATS_BACKFILLED = "daily_stats_backfilled_v1"
        private const val MAX_HISTORY_ITEMS = 200
        private const val MAX_CLIPBOARD_UNPINNED = 200
        private const val MAX_CLIP_CHARS = 4000
        private val PUNCT_OR_SPACE_ONLY = Regex("^[\\s\\p{Punct}，。？！、：；“”‘’（）【】《》…—\\-]+$")

        internal fun resolveUpgradedInputScheme(
            rawInputScheme: InputScheme,
            isXiaoheFlavor: Boolean,
            alreadyMigratedXiaoheScheme: Boolean
        ): InputScheme {
            return if (isXiaoheFlavor && !alreadyMigratedXiaoheScheme && rawInputScheme != InputScheme.RIME_CUSTOM) {
                InputScheme.FLYPY_TABLE
            } else {
                rawInputScheme
            }
        }

        fun resolveFirstRunSettings(
            systemLocale: Locale = Locale.getDefault()
        ): AppSettings {
            val isChineseSystem = systemLocale.language.lowercase(Locale.ROOT).startsWith("zh")
            return if (isChineseSystem) {
                AppSettings()
            } else {
                AppSettings(
                    flypyEnabled = false,
                    lastQwertyIsChinese = false
                )
            }
        }

        fun migrateStretchLevelToLiftLevel(
            oldMode: KeyboardHeightMode,
            oldHeightLevel: Int,
            oldLiftLevel: Int
        ): Int {
            return if (oldMode == KeyboardHeightMode.STRETCH) {
                when {
                    oldHeightLevel <= 0 -> 0
                    oldHeightLevel == 1 -> 2
                    else -> 3
                }
            } else {
                oldLiftLevel.coerceIn(0, 4)
            }
        }

        fun migrateSettingsToV340CompactLift(
            oldSettings: AppSettings,
            alreadyMigrated: Boolean
        ): AppSettings {
            if (alreadyMigrated) return oldSettings
            val migratedLift = migrateStretchLevelToLiftLevel(
                oldMode = oldSettings.keyboardHeightMode,
                oldHeightLevel = oldSettings.keyboardHeightLevel,
                oldLiftLevel = oldSettings.keyboardLiftLevel
            )
            return oldSettings.copy(
                qwertyShowNumberRow = false,
                keyboardHeightMode = KeyboardHeightMode.LIFT,
                keyboardLiftLevel = migratedLift
            )
        }

        fun sortClipboardEntries(entries: List<ClipboardEntry>): List<ClipboardEntry> {
            return entries.sortedWith(
                compareByDescending<ClipboardEntry> { it.isPinned }
                    .thenByDescending { it.timestamp }
            )
        }

        /**
         * Pure helper inserting or deduplicating a clipboard entry while preserving pinned status
         * and capping unpinned items at [maxUnpinned].
         */
        fun upsertClipboardList(
            existing: List<ClipboardEntry>,
            rawText: String,
            forcePinned: Boolean = false,
            nowMs: Long = System.currentTimeMillis(),
            maxUnpinned: Int = MAX_CLIPBOARD_UNPINNED
        ): Pair<List<ClipboardEntry>, ClipboardEntry?> {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return existing to null
            val capped = if (trimmed.length > MAX_CLIP_CHARS) trimmed.take(MAX_CLIP_CHARS) else trimmed

            val matched = existing.firstOrNull { it.text == capped }
            val activeEntry = if (matched != null) {
                matched.copy(
                    timestamp = nowMs,
                    isPinned = matched.isPinned || forcePinned
                )
            } else {
                ClipboardEntry(
                    text = capped,
                    timestamp = nowMs,
                    isPinned = forcePinned
                )
            }

            val others = existing.filterNot { it.text == capped }
            val combined = listOf(activeEntry) + others
            val pinned = combined.filter { it.isPinned }.sortedByDescending { it.timestamp }
            val unpinned = combined.filterNot { it.isPinned }
                .sortedByDescending { it.timestamp }
                .take(maxUnpinned.coerceAtLeast(1))
            return (pinned + unpinned) to activeEntry
        }

        fun pruneHistoryByRetention(
            records: List<HistoryRecord>,
            retention: HistoryRetention,
            nowMs: Long = System.currentTimeMillis()
        ): List<HistoryRecord> {
            return when (retention) {
                HistoryRetention.NEVER -> emptyList()
                HistoryRetention.FOREVER -> records
                else -> {
                    val maxAge = retention.maxAgeMs ?: return records
                    val cutoff = nowMs - maxAge
                    records.filter { it.timestamp >= cutoff }
                }
            }
        }

        /**
         * Pure diff extractor for TypeFree Auto-Dictionary Learning:
         * Given `beforeText` (just dictated) and `afterText` (edited on keyboard),
         * extracts `(replacedWrongSpan, correctedTerm)` if a concise term/name (`2..24` chars)
         * was corrected rather than an entire sentence rewrite or pure deletion.
         */
        fun extractAutoLearnedReplacement(
            beforeText: String,
            afterText: String
        ): Pair<String, String>? {
            val before = beforeText.trim()
            val after = afterText.trim()
            if (before.isEmpty() || after.isEmpty() || before == after) return null

            var prefixLen = 0
            val minLen = minOf(before.length, after.length)
            while (prefixLen < minLen && before[prefixLen] == after[prefixLen]) {
                prefixLen++
            }

            var beforeEnd = before.length
            var afterEnd = after.length
            while (
                beforeEnd > prefixLen &&
                afterEnd > prefixLen &&
                before[beforeEnd - 1] == after[afterEnd - 1]
            ) {
                beforeEnd--
                afterEnd--
            }

            // Expand to ASCII word boundaries so changing "deep seek" -> "DeepSeek" captures full token
            while (
                prefixLen > 0 &&
                (before[prefixLen - 1].isLetterOrDigit() && before[prefixLen - 1].code < 128)
            ) {
                prefixLen--
            }
            while (
                beforeEnd < before.length &&
                afterEnd < after.length &&
                ((before[beforeEnd].isLetterOrDigit() && before[beforeEnd].code < 128) ||
                    (after[afterEnd].isLetterOrDigit() && after[afterEnd].code < 128))
            ) {
                beforeEnd = (beforeEnd + 1).coerceAtMost(before.length)
                afterEnd = (afterEnd + 1).coerceAtMost(after.length)
            }

            val wrongSpan = before.substring(prefixLen, beforeEnd).trim()
            val rightSpan = after.substring(prefixLen, afterEnd).trim()

            if (wrongSpan.isEmpty() || rightSpan.isEmpty() || wrongSpan == rightSpan) return null
            if (rightSpan.length !in 2..24 || wrongSpan.length > 28) return null
            if (rightSpan.contains('\n') || wrongSpan.contains('\n')) return null
            if (PUNCT_OR_SPACE_ONLY.matches(rightSpan) || PUNCT_OR_SPACE_ONLY.matches(wrongSpan)) return null

            return wrongSpan to rightSpan
        }

        fun computeUsageStats(
            settings: AppSettings,
            history: List<HistoryRecord>
        ): UsageStatsSummary {
            val totalDictations = history.size
            val totalChars = history.sumOf { it.polishedText.length }
            val totalDurationSec = history.sumOf { it.durationMs }.coerceAtLeast(0L) / 1000.0
            val measuredWpm = if (totalDurationSec >= 3.0 && totalChars > 0) {
                ((totalChars / 1.8) / (totalDurationSec / 60.0)).roundToInt().coerceIn(135, 280)
            } else {
                220
            }
            // Manual mobile typing ~42 WPM vs voice dictation
            val savedMin = ((totalChars / 42.0) * 0.72).roundToInt().coerceAtLeast(if (totalDictations > 0) 1 else 0)

            val dictScore = (settings.dictionary.count { it.enabled } * 8).coerceAtMost(35)
            val ruleScore = (if (settings.appAwareToneEnabled) 25 else 8)
            val apiScore = when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> if (settings.sttConfig.apiKey.isNotBlank()) 25 else 10
                PipelineMode.MULTIMODAL_DIRECT -> if (settings.multimodalConfig.apiKey.isNotBlank()) 25 else 10
            }
            val historyScore = (totalDictations * 3).coerceAtMost(15)
            val overallPercent = (dictScore + ruleScore + apiScore + historyScore).coerceIn(18, 100)

            val catProgress = linkedMapOf(
                "即时社交 (微信 / Telegram)" to ((0.55f + history.count { it.styleName.contains("聊天") } * 0.08f).coerceAtMost(1f)),
                "职场协作 (飞书 / 钉钉 / Slack)" to ((0.50f + history.count { it.styleName.contains("职场") } * 0.08f).coerceAtMost(1f)),
                "邮件与文档 (Gmail / Notion)" to ((0.48f + history.count { it.styleName.contains("正式") } * 0.08f).coerceAtMost(1f)),
                "AI 助手与编程 (ChatGPT / Claude)" to ((0.62f + settings.dictionary.size * 0.05f).coerceAtMost(1f))
            )

            return UsageStatsSummary(
                totalDictations = totalDictations,
                totalCharacters = totalChars,
                averageWpm = measuredWpm,
                savedMinutes = savedMin,
                overallPersonalizationPercent = overallPercent,
                categoryProgress = catProgress
            )
        }
    }
}
