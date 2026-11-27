package com.typefree.ime.ime.autofill

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.InputType
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import com.typefree.ime.ime.KeyboardMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayList
import java.util.concurrent.Executor
import kotlin.math.roundToInt

enum class InlineSuggestionKind {
    SUGGESTION,
    ACTION
}

enum class InlineSuggestionSource {
    AUTOFILL,
    PLATFORM,
    UNKNOWN
}

data class InlineChipSizeBoundsPx(
    val minWidthPx: Int,
    val maxWidthPx: Int,
    val heightPx: Int
)

/**
 * Pure representation of Jetpack Autofill Inline UI v1 style bundle keys
 * (`androidx.autofill.inline.UiVersions` / `InlineSuggestionUi`), allowing API 30+
 * [InlinePresentationSpec] creation without an external `androidx.autofill:autofill:1.1.0` dependency.
 */
data class JetpackInlineStyleSpec(
    val supportedVersions: List<String> = listOf(INLINE_UI_VERSION_V1),
    val styleMarkerKey: String = KEY_STYLE_V1,
    val isDarkTheme: Boolean = false
) {
    fun toWireMetadataMap(): Map<String, Any> = mapOf(
        KEY_INLINE_UI_VERSIONS to supportedVersions,
        INLINE_UI_VERSION_V1 to mapOf(styleMarkerKey to true)
    )

    companion object {
        const val KEY_INLINE_UI_VERSIONS = "androidx.autofill.inline.ui.version:key"
        const val INLINE_UI_VERSION_V1 = "androidx.autofill.inline.ui.version:v1"
        const val KEY_STYLE_V1 = "style_v1"
    }
}

data class InlineRequestPlan(
    val maxSuggestionCount: Int,
    val regularChipSpecs: List<InlineChipSizeBoundsPx>,
    val tooltipSpec: InlineChipSizeBoundsPx,
    val styleSpec: JetpackInlineStyleSpec,
    val isDarkTheme: Boolean
)

data class InlineSuggestionDescriptor(
    val index: Int,
    val kind: InlineSuggestionKind,
    val source: InlineSuggestionSource = InlineSuggestionSource.AUTOFILL,
    val isPinned: Boolean = false,
    val autofillHints: List<String> = emptyList()
) {
    val isPinnedOrAction: Boolean
        get() = isPinned || kind == InlineSuggestionKind.ACTION
}

data class InflatedInlineChip(
    val descriptor: InlineSuggestionDescriptor,
    val view: InlineContentView? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

data class InlineAutofillUiState(
    val sessionId: Long = 0L,
    val isEligibleEditor: Boolean = false,
    val hasPendingInflation: Boolean = false,
    val regularChips: List<InflatedInlineChip> = emptyList(),
    val pinnedActionChips: List<InflatedInlineChip> = emptyList(),
    val isDismissedByUser: Boolean = false
) {
    val totalChipCount: Int
        get() = regularChips.size + pinnedActionChips.size

    val hasVisibleSuggestions: Boolean
        get() = !isDismissedByUser && totalChipCount > 0
}

/**
 * Platform adapter abstraction separating pure request/response planning from Android 11+ (`API 30+`)
 * Binder / Parcelable / SurfaceControl objects so unit tests execute on pure JVM without Robolectric.
 */
interface InlineAutofillPlatformAdapter<TRequest, TResponse> {
    fun buildPlatformRequest(plan: InlineRequestPlan): TRequest
    fun extractDescriptors(response: TResponse): List<InlineSuggestionDescriptor>
}

/**
 * Decoupled Android 11+ (`API 30+`) Inline Autofill controller and request builder.
 *
 * Responsibilities:
 * 1. Computes [InlineRequestPlan] pixel bounds directly from [KeyboardMetrics.candidateChipHeight]
 *    (`STANDARD` 29dp / `LARGE` 33dp / `EXTRA_LARGE` 38dp) so inline credential/OTP chips align
 *    with TypeFree's candidate bar across all font scale tiers.
 * 2. Tracks monotonic `sessionId` generations across `onStartInputSession`, `beginResponseInflation`,
 *    and `onFinishInputSession` so delayed async `InlineSuggestion.inflate(...)` callbacks from a
 *    previous editor or superseded response are discarded safely without leaking `InlineContentView`.
 * 3. Preserves provider suggestion ordering (`index`) even when async inflation completes out-of-order,
 *    and separates scrollable [InlineAutofillUiState.regularChips] from trailing [InlineAutofillUiState.pinnedActionChips].
 */
class InlineAutofillController {

    private val lock = Any()
    private var activeSessionId: Long = 0L
    private var pendingSlotIndices: MutableSet<Int> = LinkedHashSet()
    private val completedByIndex: MutableMap<Int, InflatedInlineChip> = LinkedHashMap()

    private val _state = MutableStateFlow(InlineAutofillUiState())
    val state: StateFlow<InlineAutofillUiState> = _state.asStateFlow()

    fun onStartInputSession(
        sdkInt: Int,
        inputType: Int,
        inlineAutofillEnabled: Boolean = true
    ): Long = synchronized(lock) {
        val nextSession = ++activeSessionId
        pendingSlotIndices.clear()
        completedByIndex.clear()
        val eligible = isEditorEligible(
            sdkInt = sdkInt,
            inputType = inputType,
            inlineAutofillEnabled = inlineAutofillEnabled
        )
        _state.value = InlineAutofillUiState(
            sessionId = nextSession,
            isEligibleEditor = eligible
        )
        nextSession
    }

    fun onFinishInputSession(): Long = synchronized(lock) {
        val nextSession = ++activeSessionId
        pendingSlotIndices.clear()
        completedByIndex.clear()
        _state.value = InlineAutofillUiState(
            sessionId = nextSession,
            isEligibleEditor = false
        )
        nextSession
    }

    fun clearSuggestions() = synchronized(lock) {
        val nextSession = ++activeSessionId
        pendingSlotIndices.clear()
        completedByIndex.clear()
        _state.value = _state.value.copy(
            sessionId = nextSession,
            hasPendingInflation = false,
            regularChips = emptyList(),
            pinnedActionChips = emptyList(),
            isDismissedByUser = false
        )
    }

    fun dismissCurrentSuggestions() = synchronized(lock) {
        val current = _state.value
        if (current.totalChipCount > 0 || current.hasPendingInflation) {
            _state.value = current.copy(isDismissedByUser = true)
        }
    }

    /**
     * Returns whether inline autofill strip should take over the top bar slot.
     * Active Rime/Flypy preedit composition always takes priority over idle autofill chips.
     */
    fun shouldShowInlineStrip(isComposing: Boolean): Boolean {
        return !isComposing && _state.value.hasVisibleSuggestions
    }

    fun createRequestForAdapter(
        sdkInt: Int,
        inputType: Int,
        metrics: KeyboardMetrics,
        density: Float,
        screenWidthPx: Int,
        isDarkTheme: Boolean = false,
        maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
        inlineAutofillEnabled: Boolean = true
    ): InlineRequestPlan? {
        val eligible = isEditorEligible(
            sdkInt = sdkInt,
            inputType = inputType,
            inlineAutofillEnabled = inlineAutofillEnabled
        )
        if (!eligible) return null
        return buildRequestPlan(
            metrics = metrics,
            density = density,
            screenWidthPx = screenWidthPx,
            isDarkTheme = isDarkTheme,
            maxSuggestionCount = maxSuggestions
        )
    }

    fun <TRequest, TResponse> createPlatformRequestWithAdapter(
        adapter: InlineAutofillPlatformAdapter<TRequest, TResponse>,
        sdkInt: Int,
        inputType: Int,
        metrics: KeyboardMetrics,
        density: Float,
        screenWidthPx: Int,
        isDarkTheme: Boolean = false,
        maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
        inlineAutofillEnabled: Boolean = true
    ): TRequest? {
        val plan = createRequestForAdapter(
            sdkInt = sdkInt,
            inputType = inputType,
            metrics = metrics,
            density = density,
            screenWidthPx = screenWidthPx,
            isDarkTheme = isDarkTheme,
            maxSuggestions = maxSuggestions,
            inlineAutofillEnabled = inlineAutofillEnabled
        ) ?: return null
        return adapter.buildPlatformRequest(plan)
    }

    /**
     * Starts a new inflation generation for incoming [descriptors].
     * Empty responses clear visible chips immediately (`0` pending).
     */
    fun beginResponseInflation(descriptors: List<InlineSuggestionDescriptor>): Long = synchronized(lock) {
        val nextSession = ++activeSessionId
        pendingSlotIndices.clear()
        completedByIndex.clear()
        if (descriptors.isEmpty()) {
            _state.value = _state.value.copy(
                sessionId = nextSession,
                hasPendingInflation = false,
                regularChips = emptyList(),
                pinnedActionChips = emptyList(),
                isDismissedByUser = false
            )
            return nextSession
        }
        descriptors.forEach { pendingSlotIndices.add(it.index) }
        _state.value = _state.value.copy(
            sessionId = nextSession,
            isEligibleEditor = true,
            hasPendingInflation = true,
            regularChips = emptyList(),
            pinnedActionChips = emptyList(),
            isDismissedByUser = false
        )
        nextSession
    }

    /**
     * Records completion of a single async suggestion inflation for [sessionId].
     * Returns `false` if [sessionId] is stale (e.g. editor closed or newer response arrived).
     */
    fun onSuggestionInflated(
        sessionId: Long,
        descriptor: InlineSuggestionDescriptor,
        view: InlineContentView? = null,
        widthPx: Int = 0,
        heightPx: Int = 0,
        inflationSucceeded: Boolean = true
    ): Boolean = synchronized(lock) {
        if (sessionId != activeSessionId) {
            return false
        }
        pendingSlotIndices.remove(descriptor.index)
        if (inflationSucceeded) {
            completedByIndex[descriptor.index] = InflatedInlineChip(
                descriptor = descriptor,
                view = view,
                widthPx = widthPx,
                heightPx = heightPx
            )
        }
        val ordered = completedByIndex.values.sortedBy { it.descriptor.index }
        val (pinned, regular) = ordered.partition { it.descriptor.isPinnedOrAction }
        _state.value = _state.value.copy(
            sessionId = sessionId,
            hasPendingInflation = pendingSlotIndices.isNotEmpty(),
            regularChips = regular,
            pinnedActionChips = pinned
        )
        true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun createInlineSuggestionsRequest(
        inputType: Int,
        metrics: KeyboardMetrics,
        density: Float,
        screenWidthPx: Int,
        isDarkTheme: Boolean = false,
        maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
        inlineAutofillEnabled: Boolean = true
    ): InlineSuggestionsRequest? {
        return createPlatformRequestWithAdapter(
            adapter = Android11InlineAutofillAdapter,
            sdkInt = Build.VERSION.SDK_INT,
            inputType = inputType,
            metrics = metrics,
            density = density,
            screenWidthPx = screenWidthPx,
            isDarkTheme = isDarkTheme,
            maxSuggestions = maxSuggestions,
            inlineAutofillEnabled = inlineAutofillEnabled
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun onInlineSuggestionsResponse(
        response: InlineSuggestionsResponse,
        context: Context,
        metrics: KeyboardMetrics,
        density: Float,
        callbackExecutor: Executor = context.mainExecutor,
        zOrderOnTop: Boolean = true
    ): Boolean {
        val chipHeightPx = computeChipHeightPx(metrics, density)
        Android11InlineAutofillAdapter.inflateResponseIntoController(
            controller = this,
            response = response,
            context = context,
            chipHeightPx = chipHeightPx,
            callbackExecutor = callbackExecutor,
            zOrderOnTop = zOrderOnTop
        )
        return response.inlineSuggestions.isNotEmpty()
    }

    companion object {
        const val MIN_SUPPORTED_SDK = Build.VERSION_CODES.R // API 30 (Android 11)
        const val DEFAULT_MAX_SUGGESTIONS = 6
        const val MAX_SUGGESTIONS_LIMIT = 12
        const val MIN_CHIP_HEIGHT_PX = 24
        private const val DEFAULT_FALLBACK_SCREEN_WIDTH_DP = 360f
        private const val MAX_CHIP_WIDTH_FRACTION = 0.72f
        private const val MAX_TOOLTIP_WIDTH_FRACTION = 0.85f

        fun isEditorEligible(
            sdkInt: Int,
            inputType: Int,
            inlineAutofillEnabled: Boolean = true
        ): Boolean {
            if (!inlineAutofillEnabled) return false
            if (sdkInt < MIN_SUPPORTED_SDK) return false
            if (inputType == InputType.TYPE_NULL) return false
            return true
        }

        fun computeChipHeightPx(
            metrics: KeyboardMetrics,
            density: Float
        ): Int {
            val safeDensity = density.coerceAtLeast(0.75f)
            return (metrics.candidateChipHeight.value * safeDensity)
                .roundToInt()
                .coerceAtLeast(MIN_CHIP_HEIGHT_PX)
        }

        fun buildRequestPlan(
            metrics: KeyboardMetrics,
            density: Float,
            screenWidthPx: Int,
            isDarkTheme: Boolean = false,
            maxSuggestionCount: Int = DEFAULT_MAX_SUGGESTIONS
        ): InlineRequestPlan {
            val safeDensity = density.coerceAtLeast(0.75f)
            val effectiveScreenWidthPx = if (screenWidthPx > 0) {
                screenWidthPx
            } else {
                (DEFAULT_FALLBACK_SCREEN_WIDTH_DP * safeDensity).roundToInt()
            }
            val clampedCount = maxSuggestionCount.coerceIn(1, MAX_SUGGESTIONS_LIMIT)
            val chipHeightPx = computeChipHeightPx(metrics, safeDensity)
            val minChipWidthPx = chipHeightPx
            val maxChipWidthPx = (effectiveScreenWidthPx * MAX_CHIP_WIDTH_FRACTION)
                .roundToInt()
                .coerceAtLeast(minChipWidthPx)
            val tooltipMaxWidthPx = (effectiveScreenWidthPx * MAX_TOOLTIP_WIDTH_FRACTION)
                .roundToInt()
                .coerceAtLeast(minChipWidthPx)
            val chipSpec = InlineChipSizeBoundsPx(
                minWidthPx = minChipWidthPx,
                maxWidthPx = maxChipWidthPx,
                heightPx = chipHeightPx
            )
            val tooltipSpec = InlineChipSizeBoundsPx(
                minWidthPx = minChipWidthPx,
                maxWidthPx = tooltipMaxWidthPx,
                heightPx = chipHeightPx
            )
            return InlineRequestPlan(
                maxSuggestionCount = clampedCount,
                regularChipSpecs = List(clampedCount) { chipSpec },
                tooltipSpec = tooltipSpec,
                styleSpec = JetpackInlineStyleSpec(isDarkTheme = isDarkTheme),
                isDarkTheme = isDarkTheme
            )
        }
    }
}

/**
 * Production Android 11+ (`API 30+`) framework bridge using pure Android SDK classes
 * ([InlineSuggestionsRequest], [InlinePresentationSpec], [InlineSuggestionsResponse], [InlineContentView]).
 */
@RequiresApi(Build.VERSION_CODES.R)
object Android11InlineAutofillAdapter :
    InlineAutofillPlatformAdapter<InlineSuggestionsRequest, InlineSuggestionsResponse> {

    fun buildJetpackV1StyleBundle(styleSpec: JetpackInlineStyleSpec = JetpackInlineStyleSpec()): Bundle {
        val v1Bundle = Bundle().apply {
            putBoolean(styleSpec.styleMarkerKey, true)
        }
        return Bundle().apply {
            putStringArrayList(
                JetpackInlineStyleSpec.KEY_INLINE_UI_VERSIONS,
                ArrayList(styleSpec.supportedVersions)
            )
            putBundle(JetpackInlineStyleSpec.INLINE_UI_VERSION_V1, v1Bundle)
        }
    }

    fun buildPresentationSpec(
        bounds: InlineChipSizeBoundsPx,
        styleBundle: Bundle
    ): InlinePresentationSpec {
        return InlinePresentationSpec.Builder(
            Size(bounds.minWidthPx, bounds.heightPx),
            Size(bounds.maxWidthPx, bounds.heightPx)
        )
            .setStyle(styleBundle)
            .build()
    }

    override fun buildPlatformRequest(plan: InlineRequestPlan): InlineSuggestionsRequest {
        val styleBundle = buildJetpackV1StyleBundle(plan.styleSpec)
        val specs = plan.regularChipSpecs.map { bounds ->
            buildPresentationSpec(bounds, styleBundle)
        }
        val tooltipPresentation = buildPresentationSpec(plan.tooltipSpec, styleBundle)
        return InlineSuggestionsRequest.Builder(specs)
            .setMaxSuggestionCount(plan.maxSuggestionCount)
            .setInlineTooltipPresentationSpec(tooltipPresentation)
            .setSupportedLocales(LocaleList.getDefault())
            .build()
    }

    override fun extractDescriptors(response: InlineSuggestionsResponse): List<InlineSuggestionDescriptor> {
        return response.inlineSuggestions.mapIndexed { idx, suggestion ->
            suggestion.toDescriptor(idx)
        }
    }

    fun inflateResponseIntoController(
        controller: InlineAutofillController,
        response: InlineSuggestionsResponse,
        context: Context,
        chipHeightPx: Int,
        callbackExecutor: Executor = context.mainExecutor,
        zOrderOnTop: Boolean = true
    ): Long {
        val suggestions = response.inlineSuggestions
        val descriptors = suggestions.mapIndexed { idx, suggestion -> suggestion.toDescriptor(idx) }
        val sessionId = controller.beginResponseInflation(descriptors)
        if (suggestions.isEmpty()) {
            return sessionId
        }
        val targetSize = Size(ViewGroup.LayoutParams.WRAP_CONTENT, chipHeightPx)
        suggestions.forEachIndexed { idx, suggestion ->
            val descriptor = descriptors[idx]
            suggestion.inflate(context, targetSize, callbackExecutor) { view: InlineContentView? ->
                if (view != null && zOrderOnTop) {
                    runCatching { view.setZOrderedOnTop(true) }
                }
                controller.onSuggestionInflated(
                    sessionId = sessionId,
                    descriptor = descriptor,
                    view = view,
                    widthPx = view?.measuredWidth ?: 0,
                    heightPx = chipHeightPx,
                    inflationSucceeded = view != null
                )
            }
        }
        return sessionId
    }

    private fun InlineSuggestion.toDescriptor(index: Int): InlineSuggestionDescriptor {
        val info = this.info
        val kind = when (info.type) {
            InlineSuggestionInfo.TYPE_ACTION -> InlineSuggestionKind.ACTION
            else -> InlineSuggestionKind.SUGGESTION
        }
        val source = when (info.source) {
            InlineSuggestionInfo.SOURCE_AUTOFILL -> InlineSuggestionSource.AUTOFILL
            InlineSuggestionInfo.SOURCE_PLATFORM -> InlineSuggestionSource.PLATFORM
            else -> InlineSuggestionSource.UNKNOWN
        }
        return InlineSuggestionDescriptor(
            index = index,
            kind = kind,
            source = source,
            isPinned = info.isPinned,
            autofillHints = info.autofillHints?.toList().orEmpty()
        )
    }
}
