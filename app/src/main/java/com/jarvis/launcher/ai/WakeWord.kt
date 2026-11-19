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
    /** Diagnostics: what the engine thinks it heard (shown live in Settings). */
    var onHeard: ((String) -> Unit)?
    /** The engine died (mic error / grabbed by another app); the owner should restart it. */
    var onFailure: (() -> Unit)?
    /** Prepare heavy resources off the main thread. */
    suspend fun prepare(): Boolean = true
    /** Main thread. [onWake] receives any command spoken in the same breath ("hey jarvis, open camera"). */
    fun start(onWake: (remainder: String?) -> Unit)
    fun stop()
}

// The system recogniser transcribes accurately ("Hey, Jarvis."), so any mention of the name counts.
private val WAKE = Regex("\\b(jarvis|jervis|jarvas|javis|jarvus)\\b[,.!?]?\\s*(.*)", RegexOption.IGNORE_CASE)

enum class WakeSensitivity(val label: String, val withPrefix: Double, val alone: Double, val stablePartials: Int) {
    LOW("Strict", 0.90, 0.97, 3),
    NORMAL("Balanced", 0.70, 0.88, 2),
    HIGH("Sensitive", 0.50, 0.75, 1);
    companion object { fun of(id: String?) = entries.firstOrNull { it.name == id } ?: NORMAL }
}

/**
 * Decides whether Vosk really heard "hey JARVIS". Pure functions so they are unit-tested.
 */
object WakeDecision {
    private val prefixes = setOf("hey", "okay", "ok", "hi", "hello")
    private val wakeWords = setOf("jarvis")

    /** Final result with words: {"result":[{"word":"hey","conf":0.98}, ...], "text":"hey jarvis"} */
    fun isWake(resultJson: String?, s: WakeSensitivity = WakeSensitivity.NORMAL): Boolean {
        val o = runCatching { JSONObject(resultJson ?: return false) }.getOrNull() ?: return false
        val words = o.optJSONArray("result") ?: return false
        val list = List(words.length()) { words.optJSONObject(it) }.filterNotNull()
        for ((i, w) in list.withIndex()) {
            if (w.optString("word") !in wakeWords) continue
            val conf = w.optDouble("conf", 0.0)
            val prev = list.getOrNull(i - 1)?.optString("word")
            if (prev in prefixes && conf >= s.withPrefix) return true
            // "Jarvis" on its own (the "hey" is often swallowed) needs higher confidence and a short utterance.
            if (list.size <= 2 && conf >= s.alone) return true
        }
        return false
    }

    /** Partial hypotheses carry no confidence, so only the full two-word phrase counts. */
    fun partialHasPhrase(partialJson: String?): Boolean {
        val t = runCatching { JSONObject(partialJson ?: return false).optString("partial") }.getOrDefault("")
        return Regex("\\b(hey|okay|ok|hi|hello) jarvis\\b").containsMatchIn(t)
    }

    /**
     * Grammar for the spotter: the wake phrases, generic filler that soaks up everyday speech,
     * and [unk]. Deliberately *no* close sound-alikes ("jervis", "travis"…): with accents those
     * stole real wake words.
     */
    val grammar: String = org.json.JSONArray(
        listOf(
            "hey jarvis", "okay jarvis", "ok jarvis", "hi jarvis", "hello jarvis", "jarvis",
            "hey", "hi", "okay", "ok", "hello", "yes", "no", "yeah", "right", "well", "so", "but",
            "a", "the", "and", "is", "it", "this", "that", "what", "you", "i", "of", "to", "in",
            "we", "they", "he", "she", "do", "go", "come", "here", "there", "now", "good", "one",
            "[unk]",
        )
    ).toString()
}

/**
 * Offline keyword spotter built on Vosk. Silent (no system beeps), works with the screen off,
 * low CPU. Fires on a confident final result, or on the full phrase seen in consecutive partials
 * (so it still works in noisy rooms where the utterance never "ends").
 */
class VoskWakeEngine(private val modelDir: File, private val sensitivity: () -> WakeSensitivity) : WakeEngine {
    override val label = "Vosk offline keyword spotter"
    override var onHeard: ((String) -> Unit)? = null
    override var onFailure: (() -> Unit)? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var fired = false
    private var stable = 0
    private val main = Handler(Looper.getMainLooper())

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        if (model != null) return@withContext true
        runCatching { model = Model(modelDir.absolutePath) }.isSuccess
    }

    override fun start(onWake: (String?) -> Unit) {
        val m = model ?: run { onFailure?.invoke(); return }
        stop()
        fired = false
        stable = 0
        val ok = runCatching {
            val rec = Recognizer(m, 16000f, WakeDecision.grammar)
            rec.setWords(true)
            recognizer = rec
            val svc = SpeechService(rec, 16000f)
            service = svc
            svc.startListening(object : org.vosk.android.RecognitionListener {
                private fun fire() {
                    if (fired) return
                    fired = true
                    main.post { stop(); onWake(null) }
                }
                override fun onPartialResult(hypothesis: String?) {
                    val text = runCatching { JSONObject(hypothesis ?: "{}").optString("partial") }.getOrDefault("")
                    if (text.isNotBlank()) onHeard?.invoke("… $text")
                    stable = if (WakeDecision.partialHasPhrase(hypothesis)) stable + 1 else 0
                    if (stable >= sensitivity().stablePartials) fire()
                }
                override fun onResult(hypothesis: String?) {
                    val o = runCatching { JSONObject(hypothesis ?: "{}") }.getOrNull()
                    val text = o?.optString("text").orEmpty()
                    if (text.isNotBlank()) {
                        val conf = o?.optJSONArray("result")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                                .firstOrNull { it.optString("word") == "jarvis" }?.optDouble("conf")
                        }
                        onHeard?.invoke(text + (conf?.let { " (jarvis %.0f%%)".format(it * 100) } ?: ""))
                    }
                    stable = 0
                    if (WakeDecision.isWake(hypothesis, sensitivity())) fire()
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    onHeard?.invoke("mic error: ${exception?.message ?: "unknown"}; restarting")
                    main.post { stop(); onFailure?.invoke() }
                }
                override fun onTimeout() { main.post { stop(); onFailure?.invoke() } }
            })
        }.isSuccess
        if (!ok) { stop(); onFailure?.invoke() }
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
    override var onHeard: ((String) -> Unit)? = null
    override var onFailure: (() -> Unit)? = null
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
                if (t.isNotBlank()) onHeard?.invoke(t)
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
