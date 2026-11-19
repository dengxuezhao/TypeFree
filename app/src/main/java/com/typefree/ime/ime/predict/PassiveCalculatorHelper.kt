package com.typefree.ime.ime.predict

import com.typefree.core.ime.enhancer.SafeMathEvaluator

data class PassiveCalculationResult(
    val expression: String,
    val formattedResult: String,
    val endsWithEquals: Boolean
)

/**
 * Passively detects and evaluates trailing arithmetic expressions from text before the cursor
 * (max 64 chars), e.g. `125*8=`, `价格(35+15)*2=`, `100+25`, while ignoring bare numbers or `a=50`.
 */
object PassiveCalculatorHelper {

    private const val MAX_LOOKBACK_CHARS = 64
    private const val OPERATORS = "+-*/%^×÷"

    fun evaluateTrailing(textBeforeCursor: String): PassiveCalculationResult? {
        if (textBeforeCursor.isEmpty()) return null
        val window = textBeforeCursor.takeLast(MAX_LOOKBACK_CHARS)
        if (window.isEmpty() || window.last().isWhitespace()) return null

        val endsWithEquals = window.endsWith('=') || window.endsWith('＝')
        val body = if (endsWithEquals) window.dropLast(1).trimEnd() else window
        if (body.isEmpty()) return null

        var parenBalance = 0
        var scanIdx = body.length - 1
        var rightNonSpace: Char? = null
        while (scanIdx >= 0) {
            val ch = body[scanIdx]
            when {
                ch == ')' || ch == '）' -> {
                    parenBalance++
                    rightNonSpace = ch
                    scanIdx--
                }
                ch == '(' || ch == '（' -> {
                    if (parenBalance == 0) {
                        // Unmatched opening paren belongs to surrounding text (e.g. "(10+20").
                        break
                    }
                    parenBalance--
                    rightNonSpace = ch
                    scanIdx--
                }
                ch == ' ' -> {
                    var leftIdx = scanIdx - 1
                    while (leftIdx >= 0 && body[leftIdx] == ' ') leftIdx--
                    val leftNonSpace = if (leftIdx >= 0) body[leftIdx] else null
                    if (leftNonSpace != null && rightNonSpace != null &&
                        isOperandEndChar(leftNonSpace) && isOperandStartChar(rightNonSpace)
                    ) {
                        // Break boundary between whitespace-separated operands (e.g. `1000 1*1` -> `1*1`)
                        break
                    }
                    scanIdx--
                }
                isExpressionBodyChar(ch) -> {
                    rightNonSpace = ch
                    scanIdx--
                }
                else -> break
            }
        }

        var exprStart = scanIdx + 1
        while (exprStart < body.length && body[exprStart].isWhitespace()) {
            exprStart++
        }
        if (exprStart >= body.length) return null

        // Do not trigger inside identifier-like tokens such as `v1+2` or `var_1+2`.
        if (exprStart > 0) {
            val prevChar = body[exprStart - 1]
            if (prevChar in 'a'..'z' || prevChar in 'A'..'Z' || prevChar == '_') {
                return null
            }
        }

        val rawExpr = body.substring(exprStart).trim()
        if (rawExpr.isEmpty() || !hasBinaryOperator(rawExpr)) return null

        val evaluated = SafeMathEvaluator.evaluateFormatted(rawExpr) ?: return null
        val normalizedResult = if (evaluated == "-0") "0" else evaluated
        return PassiveCalculationResult(
            expression = rawExpr,
            formattedResult = normalizedResult,
            endsWithEquals = endsWithEquals
        )
    }

    private fun isExpressionBodyChar(ch: Char): Boolean {
        return ch in '0'..'9' || ch == '.' || ch == ' ' || ch in OPERATORS
    }

    private fun isOperandEndChar(ch: Char): Boolean {
        return ch in '0'..'9' || ch == '.' || ch == ')' || ch == '）'
    }

    private fun isOperandStartChar(ch: Char): Boolean {
        return ch in '0'..'9' || ch == '.' || ch == '(' || ch == '（'
    }

    private fun hasBinaryOperator(expr: String): Boolean {
        var sawOperandBefore = false
        for (ch in expr) {
            when {
                ch in '0'..'9' || ch == ')' || ch == '）' -> sawOperandBefore = true
                ch in OPERATORS -> if (sawOperandBefore) return true
            }
        }
        return false
    }
}
