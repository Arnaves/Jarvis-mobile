package com.jarvis.launcher.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Offline text-to-speech with a preference for a British male voice. */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                configure()
            }
        }
    }

    private fun configure() {
        val t = tts ?: return
        val gb = Locale.UK
        if (t.isLanguageAvailable(gb) >= TextToSpeech.LANG_AVAILABLE) t.language = gb
        val voices: Set<Voice> = runCatching { t.voices }.getOrNull().orEmpty()
        val preferred = voices
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == "GB" }
                    .thenByDescending { it.name.contains("male", true) && !it.name.contains("female", true) }
                    .thenByDescending { it.quality }
            )
            .firstOrNull()
        preferred?.let { t.voice = it }
        t.setPitch(0.92f)
        t.setSpeechRate(1.04f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { _speaking.value = true }
            override fun onDone(id: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { _speaking.value = false }
            override fun onStop(id: String?, interrupted: Boolean) { _speaking.value = false }
        })
    }

    fun speak(text: String) {
        if (!ready) return
        val clean = text.replace(Regex("[*_#`>]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}")
    }

    fun stop() {
        tts?.stop()
        _speaking.value = false
    }
}
