package com.typefree.ime.ime.engine

import com.typefree.ime.data.SnippetEntry
import com.typefree.ime.data.SnippetMatcher
import com.typefree.ime.ime.flypy.FlypyCandidate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared Kotlin-layer candidate merger for both [FlypyInputEngine] and `RimeInputEngine`:
 * - Custom user snippets (`;sd`, `;yx`, `;dz`, or alpha triggers)
 * - Dynamic date/time/weekday commands (`orq`, `ouj`, `oxq`) without requiring librime-lua
 * - `;f` repeat last committed text & `;x` built-in quick symbols
 */
object CandidateMerger {

    val DEFAULT_SEMICOLON_QUICK_SYMBOLS: Map<String, List<String>> = mapOf(
        ";" to listOf("；", "："),
        ";q" to listOf("：“"),
        ";w" to listOf("？"),
        ";e" to listOf("（"),
        ";r" to listOf("）"),
        ";t" to listOf("～"),
        ";y" to listOf("·"),
        ";u" to listOf("『"),
        ";i" to listOf("』"),
        ";o" to listOf("〖"),
        ";p" to listOf("〗"),
        ";a" to listOf("！"),
        ";s" to listOf("……"),
        ";d" to listOf("、"),
        ";g" to listOf("“"),
        ";h" to listOf("”"),
        ";j" to listOf("‘"),
        ";k" to listOf("’"),
        ";l" to listOf("—"),
        ";z" to listOf("【"),
        ";x" to listOf("】"),
        ";c" to listOf("《"),
        ";v" to listOf("》"),
        ";b" to listOf("％"),
        ";n" to listOf("￥"),
        ";m" to listOf("—")
    )

    fun normalizeSnippets(snippets: List<SnippetEntry>): List<SnippetEntry> {
        return snippets.mapNotNull { entry ->
            if (!entry.enabled || entry.content.isBlank()) return@mapNotNull null
            val normCode = SnippetMatcher.normalizeFlypyCode(entry.flypyCode)
            if (normCode.isEmpty()) null else entry.copy(flypyCode = normCode)
        }
    }

    fun hasCustomSnippetContinuation(prefix: String, normalizedSnippets: List<SnippetEntry>): Boolean {
        if (prefix.isEmpty()) return false
        return normalizedSnippets.any {
            it.flypyCode.length > prefix.length && it.flypyCode.startsWith(prefix)
        }
    }

    fun exactSnippetsForCode(code: String, normalizedSnippets: List<SnippetEntry>): List<FlypyCandidate> {
        if (code.isEmpty()) return emptyList()
        return normalizedSnippets
            .filter { it.flypyCode == code }
            .map { entry ->
                val hint = entry.title.trim().take(6).let { if (it.isNotEmpty()) "⚡$it" else "⚡短语" }
                FlypyCandidate(text = entry.content, codeHint = hint)
            }
    }

    fun prefixSnippetsForCode(code: String, normalizedSnippets: List<SnippetEntry>): List<FlypyCandidate> {
        if (!code.startsWith(";") || code.length < 2) return emptyList()
        return normalizedSnippets
            .filter { it.flypyCode.length > code.length && it.flypyCode.startsWith(code) }
            .map { entry ->
                FlypyCandidate(text = entry.content, codeHint = entry.flypyCode)
            }
    }

    fun dynamicCommandCandidates(code: String, nowMillis: Long = System.currentTimeMillis()): List<FlypyCandidate> {
        return when (code) {
            "orq" -> {
                val date = Date(nowMillis)
                val cnFmt = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(date)
                val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                val compactFmt = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
                listOf(
                    FlypyCandidate(cnFmt),
                    FlypyCandidate(isoFmt),
                    FlypyCandidate(compactFmt)
                )
            }
            "ouj" -> {
                val date = Date(nowMillis)
                val hm = SimpleDateFormat("HH:mm", Locale.US).format(date)
                val hms = SimpleDateFormat("HH:mm:ss", Locale.US).format(date)
                listOf(
                    FlypyCandidate(hm),
                    FlypyCandidate(hms)
                )
            }
            "oxq" -> {
                val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
                val suffix = when (cal.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> "一"
                    Calendar.TUESDAY -> "二"
                    Calendar.WEDNESDAY -> "三"
                    Calendar.THURSDAY -> "四"
                    Calendar.FRIDAY -> "五"
                    Calendar.SATURDAY -> "六"
                    else -> "日"
                }
                listOf(
                    FlypyCandidate("星期$suffix"),
                    FlypyCandidate("周$suffix")
                )
            }
            else -> emptyList()
        }
    }

    fun mergeCandidates(
        rawCode: String,
        engineCandidates: List<FlypyCandidate>,
        normalizedSnippets: List<SnippetEntry>,
        lastCommittedText: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        includeBuiltInQuickSymbols: Boolean = false
    ): List<FlypyCandidate> {
        if (rawCode.isEmpty()) return engineCandidates
        val exactSnippets = exactSnippetsForCode(rawCode, normalizedSnippets)
        val prefixSnippets = prefixSnippetsForCode(rawCode, normalizedSnippets)
        if (rawCode == ";f") {
            val repeatList = if (lastCommittedText.isNotEmpty()) {
                listOf(FlypyCandidate(text = lastCommittedText))
            } else {
                emptyList()
            }
            return exactSnippets + repeatList + prefixSnippets
        }
        val dynamic = dynamicCommandCandidates(rawCode, nowMillis)
        val base = if (engineCandidates.isEmpty() && includeBuiltInQuickSymbols && rawCode.startsWith(";")) {
            DEFAULT_SEMICOLON_QUICK_SYMBOLS[rawCode]?.map { FlypyCandidate(it) }.orEmpty()
        } else {
            engineCandidates
        }
        return exactSnippets + dynamic + base + prefixSnippets
    }
}
