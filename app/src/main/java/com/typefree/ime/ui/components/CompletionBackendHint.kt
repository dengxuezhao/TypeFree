package com.typefree.ime.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typefree.core.offline.AiTask
import com.typefree.core.offline.LocalModelRegistrySnapshot
import com.typefree.core.offline.OfflineModelCatalog
import com.typefree.core.offline.RouteBlockReason
import com.typefree.core.offline.TextRoute
import com.typefree.ime.TypeFreeApp
import com.typefree.ime.data.AppSettings
import com.typefree.ime.data.OfflineRouteBridge
import com.typefree.ime.i18n.AppLang
import com.typefree.ime.i18n.AppStrings
import com.typefree.ime.i18n.LocalAppLanguage
import com.typefree.ime.ui.theme.TypeFreeAmber
import com.typefree.ime.ui.theme.TypeFreeEmerald

enum class CompletionHintTone {
    LOCAL_EMERALD,
    CLOUD_NEUTRAL,
    WARNING_AMBER
}

data class CompletionBackendHintState(
    val compactLabel: String,
    val detailedHint: String,
    val tone: CompletionHintTone
)

fun resolveCompletionBackendHintState(
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
    isNetworkAvailable: Boolean = true,
    lang: AppLang = AppStrings.resolveLang(settings.appLanguage)
): CompletionBackendHintState {
    val plan = OfflineRouteBridge.resolvePlan(
        task = AiTask.SENTENCE_COMPLETION,
        settings = settings,
        registrySnapshot = registrySnapshot,
        isNetworkAvailable = isNetworkAvailable
    )
    return when (val textRoute = plan.text) {
        is TextRoute.Local -> {
            val shortName = when (textRoute.modelId) {
                OfflineModelCatalog.ID_SLM_CORE_0_8B -> "0.8B"
                OfflineModelCatalog.ID_SLM_PLUS_2B -> "2B"
                OfflineModelCatalog.ID_SLM_PRO_4B -> "4B"
                else -> OfflineModelCatalog.findById(textRoute.modelId)?.displayName ?: textRoute.modelId
            }
            CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "本机 · 零流量", "On-Device · Zero Data", "本機 · 零流量"),
                detailedHint = AppStrings.pick(
                    lang,
                    "⚡ 本机毫秒补全 · 零流量（$shortName）",
                    "⚡ On-Device Instant Completion · Zero Data ($shortName)",
                    "⚡ 本機毫秒補全 · 零流量（$shortName）"
                ),
                tone = CompletionHintTone.LOCAL_EMERALD
            )
        }

        is TextRoute.Cloud,
        is TextRoute.CloudMultimodalTextFallback -> {
            CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "云端补全", "Cloud Completion", "雲端補全"),
                detailedHint = AppStrings.pick(
                    lang,
                    "☁️ 当前补全后端：云端模型（按停顿按需发送光标前上文）",
                    "☁️ Backend: Cloud Model (sends text before cursor on pause)",
                    "☁️ 目前補全後端：雲端模型（依停頓按需傳送游標前上文）"
                ),
                tone = CompletionHintTone.CLOUD_NEUTRAL
            )
        }

        is TextRoute.Blocked -> when (textRoute.reason) {
            RouteBlockReason.OFFLINE_ONLY_UNSUPPORTED_TASK,
            RouteBlockReason.OFFLINE_ONLY_NO_MODEL -> CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "纯离线不可用", "Offline Unavailable", "純離線不可用"),
                detailedHint = AppStrings.pick(
                    lang,
                    "🔒 纯离线锁已开启 · 当前无支持整句补全的本机大脑模型",
                    "🔒 Strict Offline Lock active · No on-device completion model installed",
                    "🔒 純離線鎖已開啟 · 目前無支援整句補全的本機大腦模型"
                ),
                tone = CompletionHintTone.WARNING_AMBER
            )

            RouteBlockReason.LOCAL_MODEL_UNAVAILABLE -> CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "本机不可用", "Local Model Error", "本機不可用"),
                detailedHint = AppStrings.pick(
                    lang,
                    "⚠️ 已选本机补全模型损坏或离线服务暂不可用",
                    "⚠️ Selected on-device model is corrupted or temporarily unavailable",
                    "⚠️ 已選本機補全模型損壞或離線服務暫不可用"
                ),
                tone = CompletionHintTone.WARNING_AMBER
            )

            RouteBlockReason.NO_CLOUD_OR_LOCAL_AVAILABLE -> CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "未配置引擎", "Not Configured", "未設定引擎"),
                detailedHint = AppStrings.pick(
                    lang,
                    "⚠️ 尚未配置云端补全模型或本机离线大脑，开启后暂无法触发",
                    "⚠️ No cloud or on-device completion model configured yet",
                    "⚠️ 尚未設定雲端補全模型或本機離線大腦，開啟後暫無法觸發"
                ),
                tone = CompletionHintTone.WARNING_AMBER
            )

            RouteBlockReason.SECURE_FIELD -> CompletionBackendHintState(
                compactLabel = AppStrings.pick(lang, "安全域禁用", "Disabled in Secure Field", "安全欄位停用"),
                detailedHint = AppStrings.pick(
                    lang,
                    "🔒 密码框与安全输入域永不触发整句补全",
                    "🔒 Never triggered in password or secure input fields",
                    "🔒 密碼框與安全輸入欄位永不觸發整句補全"
                ),
                tone = CompletionHintTone.CLOUD_NEUTRAL
            )
        }

        is TextRoute.RawPassthrough -> CompletionBackendHintState(
            compactLabel = AppStrings.pick(lang, "仅转写", "Transcribe Only", "僅轉寫"),
            detailedHint = AppStrings.pick(
                lang,
                "当前仅启用语音转写，尚未配置整句补全后端",
                "Voice transcription only — completion backend not configured",
                "目前僅啟用語音轉寫，尚未設定整句補全後端"
            ),
            tone = CompletionHintTone.WARNING_AMBER
        )
    }
}

fun resolveCompletionBackendCompactLabel(
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot = LocalModelRegistrySnapshot(),
    lang: AppLang = AppStrings.resolveLang(settings.appLanguage)
): String {
    if (!settings.aiCompletionEnabled) return AppStrings.pick(lang, "已关闭", "Off", "已關閉")
    return resolveCompletionBackendHintState(settings, registrySnapshot, lang = lang).compactLabel
}

@Composable
fun rememberLocalModelRegistrySnapshot(): LocalModelRegistrySnapshot {
    val registry = remember {
        runCatching { TypeFreeApp.instance.localModelRegistry }.getOrNull()
    }
    return if (registry != null) {
        val state by registry.snapshot.collectAsState()
        state
    } else {
        remember { LocalModelRegistrySnapshot() }
    }
}

/**
 * Shared single-line backend indicator rendered beside `aiCompletionEnabled` switches across
 * KeyboardStudioScreen, AccountSettingsScreen, and tool panel surfaces.
 */
@Composable
fun CompletionBackendHint(
    settings: AppSettings,
    registrySnapshot: LocalModelRegistrySnapshot = rememberLocalModelRegistrySnapshot(),
    modifier: Modifier = Modifier
) {
    val appLang = LocalAppLanguage.current
    val hint = remember(settings, registrySnapshot, appLang) {
        resolveCompletionBackendHintState(settings, registrySnapshot, lang = appLang)
    }

    val accentColor = when (hint.tone) {
        CompletionHintTone.LOCAL_EMERALD -> TypeFreeEmerald
        CompletionHintTone.WARNING_AMBER -> TypeFreeAmber
        CompletionHintTone.CLOUD_NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val containerColor = when (hint.tone) {
        CompletionHintTone.LOCAL_EMERALD -> TypeFreeEmerald.copy(alpha = 0.09f)
        CompletionHintTone.WARNING_AMBER -> TypeFreeAmber.copy(alpha = 0.10f)
        CompletionHintTone.CLOUD_NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val borderColor = when (hint.tone) {
        CompletionHintTone.LOCAL_EMERALD -> TypeFreeEmerald.copy(alpha = 0.36f)
        CompletionHintTone.WARNING_AMBER -> TypeFreeAmber.copy(alpha = 0.42f)
        CompletionHintTone.CLOUD_NEUTRAL -> MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = hint.detailedHint,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (hint.tone == CompletionHintTone.LOCAL_EMERALD) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                },
                color = if (hint.tone == CompletionHintTone.CLOUD_NEUTRAL) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
