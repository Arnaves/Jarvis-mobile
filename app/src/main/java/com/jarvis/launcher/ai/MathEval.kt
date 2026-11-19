package com.jarvis.launcher.ai

import kotlin.math.pow
import kotlin.math.sqrt

/** Tiny recursive-descent calculator so JARVIS can do maths fully offline. */
object MathEval {

    /** Converts spoken maths ("five times 3 plus two") into an expression and evaluates it. */
    fun evaluateSpoken(input: String): Double? {
        var s = " " + input.lowercase() + " "
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%) of\\s*(\\d+(?:\\.\\d+)?)").find(s)?.let {
            return it.groupValues[1].toDouble() / 100.0 * it.groupValues[2].toDouble()
        }
        for ((w, n) in NumberWords.units) s = s.replace(" $w ", " $n ")
        val replacements = listOf(
            "multiplied by" to "*", "times" to "*", " x " to " * ", "×" to "*",
            "divided by" to "/", "over" to "/", "÷" to "/",
            "plus" to "+", "add" to "+", "minus" to "-", "subtract" to "-",
            "to the power of" to "^", "squared" to "^2", "cubed" to "^3",
            "square root of" to "√", "root of" to "√", "mod" to "%",
        )
        for ((a, b) in replacements) {
            s = if (a.all { it.isLetter() || it == ' ' }) s.replace(Regex("\\b${a.trim()}\\b"), b) else s.replace(a, b)
        }
        s = s.replace(Regex("[^0-9+\\-*/^%().√ ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (!s.any { it.isDigit() }) return null
        if (!s.any { it in "+-*/^%√" }) return null
        return evaluate(s)
    }

    fun evaluate(expr: String): Double? = runCatching {
        val p = Parser(expr.replace(" ", ""))
        val v = p.parseExpression()
        if (p.pos != p.src.length) null else v
    }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    fun format(v: Double): String =
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.6f".format(v).trimEnd('0').trimEnd('.')

    private class Parser(val src: String) {
        var pos = 0
        fun peek() = if (pos < src.length) src[pos] else '\u0000'
        fun eat(c: Char): Boolean { if (peek() == c) { pos++; return true }; return false }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) v = when {
                eat('+') -> v + parseTerm()
                eat('-') -> v - parseTerm()
                else -> return v
            }
        }
        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) v = when {
                eat('*') -> v * parseFactor()
                eat('/') -> v / parseFactor()
                eat('%') -> v % parseFactor()
                else -> return v
            }
        }
        fun parseFactor(): Double {
            val base = parseUnary()
            return if (eat('^')) base.pow(parseFactor()) else base
        }
        fun parseUnary(): Double = when {
            eat('-') -> -parseUnary()
            eat('+') -> parseUnary()
            else -> parseAtom()
        }
        fun parseAtom(): Double {
            if (eat('√')) return sqrt(parseUnary())
            if (eat('(')) { val v = parseExpression(); eat(')'); return v }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            require(pos > start) { "number expected" }
            return src.substring(start, pos).toDouble()
        }
    }
}

object NumberWords {
    val units = listOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "forty-five" to 45, "fifty" to 50, "sixty" to 60, "ninety" to 90, "hundred" to 100,
    ).sortedByDescending { it.first.length }

    /** Parses "5", "five", "a", "an", "twenty five" into an Int. */
    fun parse(token: String): Int? {
        val t = token.trim().lowercase()
        t.toIntOrNull()?.let { return it }
        if (t == "a" || t == "an") return 1
        val parts = t.split(' ', '-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var total = 0
        for (p in parts) total += units.firstOrNull { it.first == p }?.second ?: return null
        return total
    }
}
