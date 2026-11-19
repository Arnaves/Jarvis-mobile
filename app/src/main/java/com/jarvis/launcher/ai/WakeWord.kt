package com.jarvis.launcher.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Something that listens continuously and calls back when it hears "Hey JARVIS". */
interface WakeEngine {
    val label: String
    /** Prepare heavy resources off the main thread. */
    suspend fun prepare(): Boolean = true
    /** Main thread. [onWake] receives any command spoken in the same breath ("hey jarvis, open camera"). */
    fun start(onWake: (remainder: String?) -> Unit)
    fun stop()
}

private val WAKE = Regex("\\b(?:hey|hi|ok|okay|yo)?\\s*(jarvis|jervis|jarvi|jarvas|javis)\\b[,.!]?\\s*(.*)", RegexOption.IGNORE_CASE)

/**
 * Offline keyword spotter built on Vosk with a tiny grammar. Silent (no system beeps),
 * keeps working with the screen off, and uses very little CPU.
 */
class VoskWakeEngine(private val modelDir: File) : WakeEngine {
    override val label = "Vosk offline keyword spotter"
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var fired = false

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (model != null) return@withContext true
        runCatching { model = Model(modelDir.absolutePath) }.isSuccess
    }

    override fun start(onWake: (String?) -> Unit) {
        val m = model ?: return
        stop()
        fired = false
        val grammar = "[\"hey jarvis\", \"okay jarvis\", \"hi jarvis\", \"jarvis\", \"[unk]\"]"
        val rec = Recognizer(m, 16000f, grammar)
        recognizer = rec
        val svc = SpeechService(rec, 16000f)
        service = svc
        svc.startListening(object : org.vosk.android.RecognitionListener {
            private fun check(text: String) {
                if (fired || !text.contains("jarvis")) return
                fired = true
                Handler(Looper.getMainLooper()).post { stop(); onWake(null) }
            }
            override fun onPartialResult(hypothesis: String?) {
                check(runCatching { JSONObject(hypothesis ?: "{}").optString("partial") }.getOrDefault(""))
            }
            override fun onResult(hypothesis: String?) {
                check(runCatching { JSONObject(hypothesis ?: "{}").optString("text") }.getOrDefault(""))
            }
            override fun onFinalResult(hypothesis: String?) {}
            override fun onError(exception: Exception?) { stop() }
            override fun onTimeout() {}
        })
    }

    override fun stop() {
        runCatching { service?.stop(); service?.shutdown() }
        service = null
        runCatching { recognizer?.close() }
        recognizer = null
    }
}

/**
 * Fallback that loops the platform recogniser. Works without extra downloads, but some
 * devices play a short chime each time it restarts.
 */
class RecognizerLoopWakeEngine(private val context: Context) : WakeEngine {
    override val label = "System recogniser loop"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var callback: ((String?) -> Unit)? = null

    override fun start(onWake: (String?) -> Unit) {
        stop()
        active = true
        callback = onWake
        cycle()
    }

    private fun cycle() {
        if (!active) return
        recognizer?.destroy()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            // Wait for final results so a command spoken in the same breath is captured too.
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val m = WAKE.find(t)
                if (m != null && active) {
                    val rest = m.groupValues[2].trim().takeIf { it.split(' ').size >= 2 }
                    val cb = callback
                    stop()
                    cb?.invoke(rest)
                } else restart(250)
            }
            override fun onError(error: Int) {
                restart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1500 else 300)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure { restart(2000) }
    }

    private fun restart(delayMs: Long) {
        if (!active) return
        main.postDelayed({ cycle() }, delayMs)
    }

    override fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }
}

/** Installs the ~40 MB Vosk English model: bundled in assets (CI builds) or downloaded once. */
class WakeModelStore(private val context: Context) {
    val dir: File get() = File(context.filesDir, "vosk-model")
    val ready: Boolean get() = File(dir, "conf").isDirectory || File(dir, "am").isDirectory

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    /** Copies a model bundled under assets/vosk-model, if present. */
    suspend fun installFromAssets(): Boolean = withContext(Dispatchers.IO) {
        if (ready) return@withContext true
        val assets = context.assets
        val top = runCatching { assets.list("vosk-model") }.getOrNull()
        if (top.isNullOrEmpty()) return@withContext false
        fun copy(path: String, target: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                assets.open(path).use { i -> target.outputStream().use { i.copyTo(it) } }
            } else children.forEach { copy("$path/$it", File(target, it)) }
        }
        val tmp = File(context.filesDir, "vosk-model.tmp").apply { deleteRecursively() }
        runCatching { copy("vosk-model", tmp); dir.deleteRecursively(); tmp.renameTo(dir) }
        ready
    }

    suspend fun download(url: String = DEFAULT_URL): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "vosk-model.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            var hops = 0
            while (conn.responseCode in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val size = conn.contentLengthLong
            var read = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { read += n; if (size > 0) _progress.value = read.toFloat() / size }
                    return n
                }
            }
            ZipInputStream(counting.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Strip the archive's top-level folder (vosk-model-small-en-us-0.15/...)
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.contains("..")) continue
                    val out = File(tmp, rel)
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
            conn.disconnect()
            dir.deleteRecursively()
            tmp.renameTo(dir)
            ready
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            false
        } finally {
            _progress.value = null
        }
    }

    companion object {
        const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
