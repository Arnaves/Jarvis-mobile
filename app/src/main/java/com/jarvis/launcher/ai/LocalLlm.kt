package com.jarvis.launcher.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class CoreStatus { NOT_INSTALLED, LOADING, READY, ERROR }

data class LlmState(
    val status: CoreStatus = CoreStatus.NOT_INSTALLED,
    val modelName: String? = null,
    val message: String = "Neural core not installed",
    val transferProgress: Float? = null,
)

/**
 * Wraps the MediaPipe LLM Inference runtime. Models (.task / .bin / .litertlm) are
 * stored in app-private storage and executed entirely on-device.
 */
class LocalLlm(private val context: Context) {
    private val _state = MutableStateFlow(LlmState())
    val state: StateFlow<LlmState> = _state.asStateFlow()

    private var engine: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var turns = 0
    private val lock = Mutex()

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    val isReady: Boolean get() = _state.value.status == CoreStatus.READY

    fun installedModels(): List<File> =
        modelsDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedBy { it.name }.orEmpty()

    suspend fun load(path: String, useGpu: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            closeInternal()
            val file = File(path)
            if (!file.exists()) {
                _state.value = LlmState(CoreStatus.NOT_INSTALLED, message = "Model file missing")
                return@withLock
            }
            _state.value = LlmState(CoreStatus.LOADING, file.name, "Spinning up neural core…")
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setMaxTopK(40)
                    .setPreferredBackend(if (useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
                    .build()
                engine = LlmInference.createFromOptions(context, options)
                newSession()
                _state.value = LlmState(CoreStatus.READY, file.name, "Neural core online")
            } catch (t: Throwable) {
                closeInternal()
                _state.value = LlmState(CoreStatus.ERROR, file.name, "Core failure: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun newSession() {
        session?.close()
        val e = engine ?: return
        session = LlmInferenceSession.createFromOptions(
            e,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTopP(0.95f)
                .setTemperature(0.7f)
                .build()
        )
        turns = 0
    }

    /** True when the next prompt will start a fresh context and therefore needs the persona preamble. */
    val needsPreamble: Boolean get() = turns == 0

    fun resetConversation() {
        runCatching { newSession() }
    }

    /** Streams response deltas for [prompt]. */
    fun generate(prompt: String): Flow<String> = callbackFlow {
        lock.lock()
        var released = false
        val release = { if (!released) { released = true; lock.unlock() } }
        try {
            if (turns >= MAX_TURNS) newSession()
            val s = session ?: throw IllegalStateException("Neural core offline")
            s.addQueryChunk(prompt)
            turns++
            val future = s.generateResponseAsync { partial, done ->
                if (!partial.isNullOrEmpty()) trySend(partial)
                if (done) close()
            }
            launch {
                try { future.await() } catch (t: Throwable) {
                    runCatching { newSession() }
                    close(t)
                }
            }
            awaitClose {
                if (!future.isDone) runCatching { s.cancelGenerateResponseAsync() }
                release()
            }
        } finally {
            release()
        }
    }.flowOn(Dispatchers.IO)

    fun unload() {
        runCatching { closeInternal() }
        _state.value = LlmState()
    }

    private fun closeInternal() {
        runCatching { session?.close() }
        runCatching { engine?.close() }
        session = null
        engine = null
    }

    /** Copies a user-picked model file into private storage. Returns the stored path. */
    suspend fun importFrom(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "model.task"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(modelsDir, safe)
        val tmp = File(modelsDir, "$safe.part")
        try {
            resolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out -> copyWithProgress(input, out, size) }
            }
            tmp.renameTo(target)
        } finally {
            tmp.delete()
            _state.value = _state.value.copy(transferProgress = null)
        }
        target.absolutePath
    }

    /** Downloads a model over HTTPS (one-time; inference stays offline). */
    suspend fun download(url: String, bearerToken: String?): String = withContext(Dispatchers.IO) {
        val fileName = Uri.parse(url).lastPathSegment?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "model.task"
        val target = File(modelsDir, fileName)
        val tmp = File(modelsDir, "$fileName.part")
        var conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            if (!bearerToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.connect()
            // Follow cross-host redirects manually (HttpURLConnection won't).
            var hops = 0
            while (conn.responseCode in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(url), next).openConnection() as HttpURLConnection
                conn.connect()
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val size = conn.contentLengthLong
            conn.inputStream.use { input -> tmp.outputStream().use { out -> copyWithProgress(input, out, size) } }
            tmp.renameTo(target)
        } finally {
            conn.disconnect()
            tmp.delete()
            _state.value = _state.value.copy(transferProgress = null)
        }
        target.absolutePath
    }

    fun delete(file: File) {
        if (_state.value.modelName == file.name) unload()
        file.delete()
    }

    private fun copyWithProgress(input: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        var lastEmit = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total - lastEmit > (4 shl 20)) {
                lastEmit = total
                _state.value = _state.value.copy(transferProgress = if (size > 0) total.toFloat() / size else -1f)
            }
        }
    }

    companion object {
        const val MAX_TOKENS = 2048
        const val MAX_TURNS = 6
    }
}
