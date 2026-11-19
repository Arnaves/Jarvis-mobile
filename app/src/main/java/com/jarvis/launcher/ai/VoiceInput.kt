package com.jarvis.launcher.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceState(
    val listening: Boolean = false,
    val level: Float = 0f,
    val partial: String = "",
    val error: String? = null,
)

/**
 * Speech recognition that prefers the on-device recogniser (Android 12+) and
 * otherwise asks the system recogniser to stay offline.
 */
class VoiceInput(private val context: Context) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var triedFallback = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private fun onDeviceAvailable() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun start(onResult: (String) -> Unit) {
        stop()
        triedFallback = false
        begin(preferOnDevice = true, onResult = onResult)
    }

    private fun begin(preferOnDevice: Boolean, onResult: (String) -> Unit) {
        recognizer?.destroy()
        usingOnDevice = preferOnDevice && onDeviceAvailable()
        val r = if (usingOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _state.value = VoiceState(listening = true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                val lvl = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.value = _state.value.copy(level = _state.value.level * 0.5f + lvl * 0.5f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _state.value = _state.value.copy(level = 0f) }
            override fun onError(error: Int) {
                val recoverable = usingOnDevice && !triedFallback &&
                    error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                if (recoverable) {
                    triedFallback = true
                    begin(preferOnDevice = false, onResult = onResult)
                    return
                }
                _state.value = VoiceState(error = describe(error))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = VoiceState()
                if (text.isNotBlank()) onResult(text) else _state.value = VoiceState(error = "I didn't catch that.")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) _state.value = _state.value.copy(partial = text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = VoiceState(listening = true)
        runCatching { r.startListening(intent) }.onFailure {
            _state.value = VoiceState(error = "Voice systems unavailable.")
        }
    }

    fun stop() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        if (_state.value.listening) _state.value = VoiceState()
    }

    fun clearError() { if (_state.value.error != null) _state.value = VoiceState() }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Offline speech pack missing. Install it under Settings › Languages › On-device recognition."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice channel busy. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone fault detected."
        else -> "Voice recognition error ($code)."
    }
}
