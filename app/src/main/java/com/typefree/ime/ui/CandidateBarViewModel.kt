package com.typefree.ime.ui

import com.typefree.core.ime.coordinator.CoordinatorModeBadge
import com.typefree.core.ime.coordinator.CoordinatorUiSnapshot
import com.typefree.ime.ime.flypy.FlypyCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CandidateBarRenderState(
    val isComposing: Boolean = false,
    val modeBadge: String? = null,
    val rawCode: String = "",
    val preeditText: String = "",
    val candidates: List<FlypyCandidate> = emptyList(),
    val isReverseSplit: Boolean = false,
    val hasMore: Boolean = false,
    val isOrqDateActive: Boolean = false,
    val orqDateOffsetDays: Int = 0
)

/**
 * Lightweight ViewModel/mapper converting `:core` [CoordinatorUiSnapshot] into the fixed `252.dp`
 * keyboard top-bar candidate representation (mode badge `「符」`/`「反查」`/`「直通」`, `hf'kn` split
 * preedit, and suffix-only secondary candidate display texts).
 */
class CandidateBarViewModel {

    private val _uiState = MutableStateFlow(CandidateBarRenderState())
    val uiState: StateFlow<CandidateBarRenderState> = _uiState.asStateFlow()

    fun updateFromCoordinator(snapshot: CoordinatorUiSnapshot): CandidateBarRenderState {
        val mapped = mapSnapshot(snapshot)
        _uiState.value = mapped
        return mapped
    }

    fun reset() {
        _uiState.value = CandidateBarRenderState()
    }

    companion object {
        fun mapSnapshot(snapshot: CoordinatorUiSnapshot): CandidateBarRenderState {
            if (!snapshot.isComposing) {
                return CandidateBarRenderState()
            }
            val badgeLabel = snapshot.badge.label.ifEmpty { null }
            val formattedPreedit = if (badgeLabel != null) {
                "[$badgeLabel] ${snapshot.preedit}"
            } else {
                snapshot.preedit
            }
            val mappedCandidates = snapshot.candidates.map { ec ->
                FlypyCandidate(
                    text = ec.commitText.ifEmpty { ec.displayText },
                    codeHint = ec.codeHint,
                    displayText = ec.displayText
                )
            }
            return CandidateBarRenderState(
                isComposing = true,
                modeBadge = badgeLabel,
                rawCode = snapshot.rawCode,
                preeditText = formattedPreedit,
                candidates = mappedCandidates,
                isReverseSplit = snapshot.isReverseSplitActive,
                hasMore = snapshot.hasMoreCandidates,
                isOrqDateActive = snapshot.isOrqDateActive,
                orqDateOffsetDays = snapshot.orqDateOffsetDays
            )
        }
    }
}
