package com.jarvis.launcher.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.launcher.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiState(
    val hasKey: Boolean = false,
    val enabled: Boolean = true,
    val model: String = GeminiClient.DEFAULT_MODEL,
    val models: List<String> = emptyList(),
    val status: String = "No API key",
    val verified: Boolean = false,
    /** The model that actually answered last (differs from [model] after a quota failover). */
    val activeModel: String? = null,
    /** Models that support the Live (bidiGenerateContent) API, best first. */
    val liveModels: List<String> = emptyList(),
)

/**
 * Google Gemini via the Generative Language REST API, streamed over SSE, with optional
 * Google Search grounding for live answers. The API key is stored with [SecretStore].
 */
class GeminiClient(private val context: Context) {
    private val secrets = SecretStore(context)
    private val sp = context.getSharedPreferences("jarvis_gemini", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        GeminiState(
            hasKey = secrets.has(KEY),
            enabled = sp.getBoolean("enabled", true),
            model = sp.getString("model", DEFAULT_MODEL)!!,
            models = sp.getString("models", "")!!.split(',').filter { it.isNotBlank() },
            verified = sp.getBoolean("verified", false),
            liveModels = sp.getString("live_models", "")!!.split(',').filter { it.isNotBlank() },
            status = if (secrets.has(KEY)) "Key stored" else "No API key",
        )
    )
    val state: StateFlow<GeminiState> = _state.asStateFlow()

    /** One conversation turn; [image] (JPEG bytes) is sent as inline data for VISION. */
    data class Turn(val user: Boolean, val text: String, val image: ByteArray? = null)

    private val online: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** Ready to answer right now (key present, enabled, network up). */
    val available: Boolean get() = _state.value.hasKey && _state.value.enabled && online

    /** For the Live WebSocket, which authenticates with the key as a query parameter. */
    internal fun apiKey(): String? = secrets.get(KEY)

    fun setKey(key: String) {
        secrets.put(KEY, key.trim())
        sp.edit().putBoolean("verified", false).apply()
        _state.value = _state.value.copy(hasKey = key.isNotBlank(), verified = false, status = if (key.isNotBlank()) "Key stored. Tap VERIFY." else "No API key")
    }

    fun clearKey() {
        secrets.put(KEY, null)
        sp.edit().putBoolean("verified", false).apply()
        _state.value = _state.value.copy(hasKey = false, verified = false, status = "No API key")
    }

    fun setEnabled(v: Boolean) { sp.edit().putBoolean("enabled", v).apply(); _state.value = _state.value.copy(enabled = v) }
    fun setModel(m: String) { sp.edit().putString("model", m).apply(); _state.value = _state.value.copy(model = m) }

    /** Checks the key and lists the chat models it can use. */
    private var liveFound: List<String> = emptyList()

    suspend fun verify(): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = secrets.get(KEY) ?: return@withContext Result.failure(IllegalStateException("No API key"))
        _state.value = _state.value.copy(status = "Verifying…")
        runCatching {
            val c = (URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection).apply {
                setRequestProperty("x-goog-api-key", key)
                connectTimeout = 10_000; readTimeout = 15_000
            }
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException(errorMessage(c))
                val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                fun supports(m: JSONObject, method: String): Boolean {
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: return false
                    return (0 until methods.length()).any { methods.optString(it) == method }
                }
                liveFound = all.filter { supports(it, "bidiGenerateContent") }.map { it.optString("name").removePrefix("models/") }
                    .sortedWith(compareBy<String> { if (it.contains("native-audio")) 0 else if (it.contains("live")) 1 else 2 }.thenByDescending { it })
                all.filter { supports(it, "generateContent") }
                    .map { it.optString("name").removePrefix("models/") }
                    .filter { it.startsWith("gemini") && !it.contains("embedding") && !it.contains("tts") && !it.contains("image") }
                    .let(::rank)
            } finally { c.disconnect() }
        }.onSuccess { list ->
            val current = _state.value.model
            val pick = if (current in list) current else list.firstOrNull { it.contains("flash") && !it.contains("lite") } ?: list.firstOrNull() ?: current
            sp.edit().putBoolean("verified", true).putString("models", list.joinToString(",")).putString("model", pick)
                .putString("live_models", liveFound.joinToString(",")).apply()
            _state.value = _state.value.copy(
                verified = true, models = list, model = pick, liveModels = liveFound,
                status = "Connected · ${list.size} models" + if (liveFound.isNotEmpty()) " · Live voice available" else "",
            )
        }.onFailure {
            _state.value = _state.value.copy(verified = false, status = "Verification failed: ${it.message}")
        }
    }

    private class HttpError(val code: Int, message: String, val retryAfterMs: Long?) : Exception(message)

    /** Models that hit their quota (or vanished), and until when to skip them. */
    private val cooldown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Preferred model first, then the rest ranked by how much free-tier quota they usually have:
     * flash-lite, then flash, then everything else, with pro last.
     */
    fun candidates(now: Long = System.currentTimeMillis()): List<String> {
        val st = _state.value
        val pool = (st.models.ifEmpty { FALLBACK_MODELS } + st.model).distinct()
        return (listOf(st.model) + rank(pool)).distinct().filter { (cooldown[it] ?: 0L) <= now }
    }

    /**
     * Streams the reply text for [history] (ending with the user's turn).
     * [grounded] enables Google Search so answers about current events are live.
     * On quota (429), missing model (404) or overload (5xx) before any text arrives, it retries
     * without search grounding, then fails over to the next model automatically.
     */
    fun stream(system: String, history: List<Turn>, grounded: Boolean, think: Boolean = false): Flow<String> = flow {
        val key = secrets.get(KEY) ?: throw IllegalStateException("No Gemini API key")
        val models = candidates()
        if (models.isEmpty()) {
            val soonest = cooldown.values.minOrNull()?.let { ((it - System.currentTimeMillis()) / 1000).coerceAtLeast(1) }
            throw IllegalStateException("All Gemini models are out of quota" + (soonest?.let { "; retrying in ${it}s" } ?: ""))
        }
        var lastError: Throwable? = null
        var emitted = false
        for (model in models) {
            val modes = if (grounded) listOf(true, false) else listOf(false)
            var thinkHere = think
            for (g in modes) {
                try {
                    try {
                        streamOnce(key, model, system, history, g, thinkHere).collect { emitted = true; emit(it) }
                    } catch (e: HttpError) {
                        // Some models don't accept a thinking config: retry plainly.
                        if (e.code == 400 && thinkHere && !emitted) {
                            thinkHere = false
                            streamOnce(key, model, system, history, g, false).collect { emitted = true; emit(it) }
                        } else throw e
                    }
                    if (_state.value.activeModel != model) {
                        _state.value = _state.value.copy(
                            activeModel = model,
                            status = if (model == _state.value.model) "Connected" else "Using $model (${_state.value.model} out of quota)",
                        )
                    }
                    return@flow
                } catch (e: HttpError) {
                    if (emitted) throw e
                    lastError = e
                    when (e.code) {
                        // Grounding has its own quota: first retry the same model without search.
                        429 -> if (g && grounded) continue else {
                            cooldown[model] = System.currentTimeMillis() + (e.retryAfterMs ?: 60_000L)
                            break
                        }
                        404, 500, 502, 503, 504 -> {
                            cooldown[model] = System.currentTimeMillis() + if (e.code == 404) 6 * 3_600_000L else 30_000L
                            break
                        }
                        else -> throw e
                    }
                }
            }
        }
        throw IllegalStateException(
            if ((lastError as? HttpError)?.code == 429) "All Gemini models are out of quota for now" else lastError?.message ?: "Gemini unavailable"
        )
    }.flowOn(Dispatchers.IO)

    /** Thoughts (THINK brain) are emitted wrapped in <think>…</think>. */
    private fun streamOnce(key: String, model: String, system: String, history: List<Turn>, grounded: Boolean, think: Boolean): Flow<String> = flow {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().apply {
                history.forEach { t ->
                    val parts = JSONArray()
                    t.image?.let { img ->
                        parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg")
                            .put("data", android.util.Base64.encodeToString(img, android.util.Base64.NO_WRAP))))
                    }
                    parts.put(JSONObject().put("text", t.text))
                    put(JSONObject().put("role", if (t.user) "user" else "model").put("parts", parts))
                }
            })
            if (grounded) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", if (think) 1.0 else 0.7).put("maxOutputTokens", if (think) 4096 else 2048).apply {
                if (think) put("thinkingConfig", JSONObject().put("includeThoughts", true).put("thinkingBudget", 2048))
            })
        }
        val c = (URL("$BASE/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
            connectTimeout = 10_000; readTimeout = 60_000
        }
        currentCoroutineContext().job.invokeOnCompletion { runCatching { c.disconnect() } }
        var inThink = false
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw HttpError(c.responseCode, describe(c.responseCode, raw), retryDelayMs(raw))
            }
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val o = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val t = part.optString("text")
                        if (t.isEmpty()) continue
                        val thought = part.optBoolean("thought", false)
                        if (thought && !inThink) { emit("<think>"); inThink = true }
                        if (!thought && inThink) { emit("</think>"); inThink = false }
                        emit(t)
                    }
                }
            }
            if (inThink) emit("</think>")
        } finally { c.disconnect() }
    }

    private fun errorMessage(c: HttpURLConnection): String =
        describe(c.responseCode, runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty())

    private fun describe(code: Int, raw: String): String {
        val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            400 -> msg ?: "Bad request"
            401, 403 -> "API key rejected" + (msg?.let { ": $it" } ?: "")
            404 -> "Model not found"
            429 -> "Quota exceeded"
            else -> "HTTP $code" + (msg?.let { ": $it" } ?: "")
        }.trimEnd('.')
    }

    companion object {
        private const val KEY = "gemini_api_key"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Used until VERIFY fetches the real model list for the key. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        /** Tried (in ranked order) when the key's model list hasn't been fetched yet. */
        val FALLBACK_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.0-flash-lite")

        /** Free-tier friendliness: flash-lite > flash > other > pro; stable before preview; newer first. */
        fun rank(models: List<String>): List<String> = models.sortedWith(
            compareBy<String> {
                when {
                    it.contains("flash-lite") -> 0
                    it.contains("flash") -> 1
                    it.contains("pro") -> 3
                    else -> 2
                }
            }.thenBy { if (it.contains("preview") || it.contains("exp")) 1 else 0 }
                .thenByDescending { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.value?.toDoubleOrNull() ?: 0.0 }
        )

        /** Parses RetryInfo.retryDelay ("37s") from a Gemini error body. */
        fun retryDelayMs(raw: String): Long? = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(raw)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() + 1000 }
    }
}
