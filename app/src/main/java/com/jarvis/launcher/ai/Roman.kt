package com.jarvis.launcher.ai

import android.os.Build

/**
 * "Your language, in Roman letters": converts native-script text (Hindi, Marathi, Bengali,
 * Tamil, …) to plain Roman letters, keeping the language ("क्या कर रहे हो" -> "kya kar rahe ho").
 */
object Roman {
    private val nonLatin = Regex("[\\p{IsDevanagari}\\p{IsBengali}\\p{IsGujarati}\\p{IsGurmukhi}\\p{IsTamil}\\p{IsTelugu}\\p{IsKannada}\\p{IsMalayalam}\\p{IsOriya}\\p{IsArabic}\\p{IsCyrillic}]")

    fun needs(text: String) = nonLatin.containsMatchIn(text)

    fun toRoman(text: String): String {
        if (!needs(text) || Build.VERSION.SDK_INT < 29) return text
        val t = runCatching { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull() ?: return text
        return text.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { word ->
            if (word.isBlank() || !needs(word)) word
            else schwa(word, t.transliterate(word))
        }
    }

    /**
     * Indic scripts write an inherent "a" after consonants, but Hindi/Marathi usually drop it at the
     * end of a word: "कर" -> "kara" -> "kar". Drop it when the native word ends in a bare consonant.
     */
    private fun schwa(native: String, latin: String): String {
        val last = native.trimEnd { !Character.isLetter(it) && Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lastOrNull() ?: return latin
        val bareConsonant = last.code in 0x0915..0x0939 || last.code in 0x0958..0x095F
        val trail = latin.takeLastWhile { !it.isLetter() }
        val core = latin.dropLast(trail.length)
        return if (bareConsonant && core.length > 2 && core.endsWith("a")) core.dropLast(1) + trail else latin
    }
}
