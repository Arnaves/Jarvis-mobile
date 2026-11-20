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
            status = if (secrets.has(KEY)) "Key stored" else "No API key",
        )
    )
    val state: StateFlow<GeminiState> = _state.asStateFlow()

    data class Turn(val user: Boolean, val text: String)

    private val online: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** Ready to answer right now (key present, enabled, network up). */
    val available: Boolean get() = _state.value.hasKey && _state.value.enabled && online

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
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .filter { m ->
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        (0 until (methods?.length() ?: 0)).any { methods!!.optString(it) == "generateContent" }
                    }
                    .map { it.optString("name").removePrefix("models/") }
                    .filter { it.startsWith("gemini") && !it.contains("embedding") && !it.contains("tts") && !it.contains("image") }
                    .sortedWith(compareByDescending<String> { it.contains("flash") && !it.contains("lite") }.thenByDescending { it })
            } finally { c.disconnect() }
        }.onSuccess { list ->
            val current = _state.value.model
            val pick = if (current in list) current else list.firstOrNull { it.contains("flash") && !it.contains("lite") } ?: list.firstOrNull() ?: current
            sp.edit().putBoolean("verified", true).putString("models", list.joinToString(",")).putString("model", pick).apply()
            _state.value = _state.value.copy(verified = true, models = list, model = pick, status = "Connected · ${list.size} models available")
        }.onFailure {
            _state.value = _state.value.copy(verified = false, status = "Verification failed: ${it.message}")
        }
    }

    /**
     * Streams the reply text for [history] (ending with the user's turn).
     * [grounded] enables Google Search so answers about current events are live.
     */
    fun stream(system: String, history: List<Turn>, grounded: Boolean): Flow<String> = flow {
        val key = secrets.get(KEY) ?: throw IllegalStateException("No Gemini API key")
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().apply {
                history.forEach { t ->
                    put(JSONObject().put("role", if (t.user) "user" else "model").put("parts", JSONArray().put(JSONObject().put("text", t.text))))
                }
            })
            if (grounded) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1024))
        }
        val model = _state.value.model
        val c = (URL("$BASE/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
            connectTimeout = 10_000; readTimeout = 60_000
        }
        currentCoroutineContext().job.invokeOnCompletion { runCatching { c.disconnect() } }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) throw IllegalStateException(errorMessage(c))
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (!line.startsWith("data:")) continue
                    val o = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                    val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val t = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (t.isNotEmpty()) emit(t)
                    }
                }
            }
        } finally { c.disconnect() }
    }.flowOn(Dispatchers.IO)

    private fun errorMessage(c: HttpURLConnection): String {
        val raw = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
        val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
        return when (c.responseCode) {
            400 -> msg?.takeIf { it.isNotBlank() } ?: "Bad request"
            401, 403 -> "API key rejected" + (msg?.let { ": $it" } ?: "")
            404 -> "Model not found. Pick another in Settings."
            429 -> "Quota exceeded. Try again later."
            else -> "HTTP ${c.responseCode}" + (msg?.let { ": $it" } ?: "")
        }
    }

    companion object {
        private const val KEY = "gemini_api_key"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        /** Used until VERIFY fetches the real model list for the key. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
