package com.typefree.core.ime.coordinator

enum class DirectCommandType {
    TOGGLE_FULL_CODE_DICT, // oqm: 热切换 flypy_full 全码字分类词库开关
    TOGGLE_ASCII_PUNCT,    // ovy: 中英标点切换
    TOGGLE_SIMPLIFICATION, // ojf: 简繁切换 (s2tw.json)
    OPEN_JEV_COPILOT,      // oai: 一键联动 ✦ Jev 军师润色
    TOGGLE_SINGLE_CHAR_MODE // odz: 单字模式开关
}

/**
 * Platform-agnostic commit action emitted by `:core` (`OverlaySymbolController`,
 * `DirectCommandEngine`, `ReverseSplitEngine`, and `TypeFreeKeyCoordinator`) and executed
 * on Android by `AndroidCommitExecutor` (or macOS host executor).
 */
sealed interface CommitAction {
    data class PlainText(
        val text: String,
        val fromRimeNative: Boolean = false
    ) : CommitAction

    data class PairedText(
        val left: String,
        val right: String
    ) : CommitAction

    data object UndoLastCommit : CommitAction

    data object RepeatLastCommit : CommitAction

    data class CopyToClipboard(
        val text: String,
        val toastMessage: String = "✓ 已复制拆码: $text"
    ) : CommitAction

    data class Command(
        val commandType: DirectCommandType,
        val payload: String = ""
    ) : CommitAction

    data class Composite(
        val steps: List<CommitAction>
    ) : CommitAction
}

/**
 * Candidate model carrying separate `displayText` (rendered on candidate bar, e.g. suffix-only `困难`
 * for reverse-split #2) and `commitAction` (actual action executed upon selection, e.g. `PlainText("很困难")`).
 */
data class EnhancedCandidate(
    val displayText: String,
    val commitText: String = displayText,
    val codeHint: String = "",
    val action: CommitAction = CommitAction.PlainText(commitText)
)

/**
 * Slash-guided snippet entry (`/` prefix mode) where [code] is the pure lowercase letters
 * after stripping any leading `/` or `;` (e.g. `sd`, `yx`, `dz`).
 */
data class SlashSnippetEntry(
    val code: String,
    val title: String,
    val content: String
)
