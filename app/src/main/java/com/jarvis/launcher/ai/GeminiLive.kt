package com.jarvis.launcher.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

/**
 * Gemini Live: a real-time spoken conversation. Microphone audio (16 kHz PCM) streams to
 * Gemini over a WebSocket; Gemini's voice (24 kHz PCM) streams back and plays immediately.
 * Gemini can call JARVIS's phone commands as a tool mid-conversation.
 *
 * Half-duplex: the mic isn't sent while JARVIS is speaking, so the phone speaker can't
 * feed his own voice back into the conversation.
 */
class GeminiLive(private val context: Context, private val gemini: GeminiClient) {
    enum class State { IDLE, CONNECTING, LISTENING, SPEAKING, ERROR }

    data class Line(val user: Boolean, val text: String)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Runs a phone command for Gemini and returns the result to tell it. Called on the main thread. */
    var onCommand: (suspend (String) -> String)? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    /** Finished user/model utterances for the chat log. */
    var onLine: ((Line) -> Unit)? = null
    /** Session ended (by the user, silence timeout, or an error). */
    var onEnded: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var speakingUntil = 0L
    @Volatile private var lastActivity = 0L
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private val userText = StringBuilder()
    private val modelText = StringBuilder()
    private var candidates: List<String> = emptyList()
    @Volatile private var setupDone = false
    /** Bumped per session so threads from an earlier session always exit. */
    @Volatile private var session = 0
    private var bargeIn = false
    /** Mic audio captured while the connection is still being set up (sent as soon as it's ready). */
    private val pending = java.util.concurrent.ConcurrentLinkedDeque<ByteArray>()
    /** Gemini's voice, played on its own thread so the socket never waits on the speaker. */
    private val playQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var system = ""
    private var voice = "Charon"
    private var idleMs = 45_000L

    val active: Boolean get() = running

    fun start(systemInstruction: String, voiceName: String = "Charon", idleTimeoutMs: Long = 45_000, allowBargeIn: Boolean? = null) {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission needed"); return
        }
        if (gemini.apiKey() == null) { fail("No Gemini API key"); return }
        system = systemInstruction; voice = voiceName; idleMs = idleTimeoutMs
        bargeIn = allowBargeIn ?: headphonesConnected()
        candidates = gemini.state.value.liveModels.ifEmpty { FALLBACK_LIVE_MODELS }
        // Remember which model/config worked last time and go straight to it.
        val lastModel = lastGood?.first
        if (lastModel != null && lastModel in candidates) candidates = listOf(lastModel) + (candidates - lastModel)
        running = true
        pending.clear(); playQueue.clear()
        // Start listening right away: what you say while we connect is buffered, not lost.
        startAudio()
        if (!running) return
        connect(0)
    }

    /** Wired/Bluetooth/USB headphones: no speaker echo, so interrupting JARVIS is safe. */
    private fun headphonesConnected(): Boolean {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return false
        return am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            ) || (android.os.Build.VERSION.SDK_INT >= 31 && it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        teardownAudio()
        flushLines()
        _state.value = State.IDLE
        main.post { onEnded?.invoke() }
    }

    private fun fail(msg: String) {
        _status.value = msg
        _state.value = State.ERROR
        running = false
        teardownAudio()
        main.post { onEnded?.invoke() }
    }

    // ------------------------------------------------------------------ connection

    /** Attempt [index] → (model, tier). Tier 0 = low-latency VAD + no thinking, 1 = low-latency VAD, 2 = plain. */
    private fun attempt(index: Int): Pair<String, Int>? {
        val m = candidates.getOrNull(index / 3) ?: return null
        val tier = index % 3
        val good = lastGood
        // Skip tiers known to fail for this model.
        if (good != null && good.first == m && tier < good.second) return attempt(index + (good.second - tier))
        return m to tier
    }

    private fun connect(index: Int) {
        if (!running) return
        val (model, tier) = attempt(index) ?: run { fail("No Live model available for this key"); return }
        _state.value = State.CONNECTING
        _status.value = "Connecting to $model…"
        setupDone = false
        val key = gemini.apiKey() ?: run { fail("No Gemini API key"); return }
        val req = Request.Builder().url("$WS_URL?key=$key").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model, tier).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text, model, tier)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8(), model, tier)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (running && !setupDone) main.post { connect(index + 1) }          // model rejected: try the next
                else if (running) main.post { fail("Live session closed: ${reason.ifBlank { "code $code" }}") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running && !setupDone && response?.code in setOf(400, 404)) main.post { connect(index + 1) }
                else if (running) main.post { fail("Live connection failed: ${t.message ?: response?.message ?: "error"}") }
            }
        })
    }

    private fun setupMessage(model: String, tier: Int) = JSONObject().put("setup", JSONObject().apply {
        put("model", "models/$model")
        put("generationConfig", JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
            .apply { if (tier == 0) put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) })
        if (tier <= 1) {
            // Snappy turn-taking: respond after ~0.4 s of silence instead of the default longer pause.
            put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 100)
                .put("silenceDurationMs", 400)))
        }
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        put("tools", JSONArray()
            .put(JSONObject().put("functionDeclarations", JSONArray().put(JSONObject()
                .put("name", "phone_command")
                .put("description", "Run a command on the user's Android phone through JARVIS. Use it whenever the user asks the phone to do something. " +
                    "Examples: 'open WhatsApp', 'set a timer for 5 minutes', 'set an alarm for 6:30 am', 'call Mum', " +
                    "'send a whatsapp to Rahul saying I'm on my way', 'flashlight on', 'volume 40 percent', 'brightness 60 percent', " +
                    "'play Shape of You on Spotify', 'pause', 'next track', 'what's playing', 'read the screen', 'tap Sign in', " +
                    "'type hello', 'scroll down', 'go back', 'go home', 'lock the phone', 'take a screenshot', 'take a photo', " +
                    "'navigate to the airport', 'remember my car is on level 3', 'note for tomorrow call the bank', " +
                    "'what do you have for me today', 'at 6 pm text Riya saying running late', 'when I reach office send a whatsapp to Mummy saying reached'.")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("command", JSONObject().put("type", "STRING").put("description", "The command in plain English")))
                    .put("required", JSONArray().put("command"))))))
            .put(JSONObject().put("googleSearch", JSONObject())))
        put("inputAudioTranscription", JSONObject())
        put("outputAudioTranscription", JSONObject())
    })

    private fun handle(raw: String, model: String, tier: Int) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (o.has("setupComplete")) {
            lastGood = model to tier
            _status.value = "Live · $model"
            // Send everything said while connecting, then go live.
            while (true) { val chunk = pending.pollFirst() ?: break; sendAudio(chunk, chunk.size) }
            setupDone = true
            return
        }
        o.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) {
                playQueue.clear()
                player?.let { runCatching { it.pause(); it.flush(); it.play() } }
                speakingUntil = 0
            }
            sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                userText.append(it); lastActivity = System.currentTimeMillis()
            }
            sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { modelText.append(it) }
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data") ?: continue
                    if (data.isNotEmpty()) play(Base64.decode(data, Base64.DEFAULT))
                }
            }
            if (sc.optBoolean("turnComplete")) main.post { flushLines() }
        }
        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            scope.launch {
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    val cmd = c.optJSONObject("args")?.optString("command").orEmpty()
                    val result = runCatching { onCommand?.invoke(cmd) }.getOrNull() ?: "Done."
                    responses.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name"))
                        .put("response", JSONObject().put("result", result)))
                }
                ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
                lastActivity = System.currentTimeMillis()
            }
        }
        o.optJSONObject("goAway")?.let { main.post { fail("Live session time limit reached") } }
    }

    private fun flushLines() {
        if (userText.isNotBlank()) onLine?.invoke(Line(true, userText.toString().trim()))
        if (modelText.isNotBlank()) onLine?.invoke(Line(false, modelText.toString().trim()))
        userText.clear(); modelText.clear()
    }

    // ------------------------------------------------------------------ audio

    private fun sendAudio(buf: ByteArray, n: Int) {
        val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (!running || recorder != null) return
        val inRate = 16000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { fail("Microphone unavailable"); return }
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        recorder = rec

        val outRate = 24000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minOut * 2, 9600)) // small buffer = low output latency
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply { if (android.os.Build.VERSION.SDK_INT >= 26) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build().also { it.play() }
        player = track
        val mySession = ++session
        thread(name = "live-speaker") {
            while (running && session == mySession) {
                val chunk = runCatching { playQueue.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { track.write(chunk, 0, chunk.size) }
            }
        }

        _state.value = State.CONNECTING
        lastActivity = System.currentTimeMillis()
        rec.startRecording()
        thread(name = "live-mic") {
            val buf = ByteArray(1600) // 50 ms chunks: lower latency than 100 ms
            while (running && session == mySession) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break      // recorder released or failed
                if (n == 0) continue
                val now = System.currentTimeMillis()
                val speaking = now < speakingUntil
                if (setupDone) _state.value = if (speaking) State.SPEAKING else State.LISTENING
                if (speaking && !bargeIn) {
                    // Half-duplex on the loudspeaker: don't let JARVIS hear himself.
                } else if (!setupDone) {
                    pending.addLast(buf.copyOf(n))
                    while (pending.size > 200) pending.pollFirst() // keep at most ~10 s
                } else sendAudio(buf, n)
                if (setupDone && !speaking && now - lastActivity > idleMs) { main.post { stop() }; break }
            }
        }
    }

    private fun play(pcm: ByteArray) {
        // Track when queued speech will finish so the mic stays muted until then (+ a short tail).
        val ms = pcm.size / 48L // 24 kHz * 2 bytes = 48 bytes per ms
        val now = System.currentTimeMillis()
        speakingUntil = maxOf(speakingUntil, now) + ms + if (bargeIn) 0 else 150
        lastActivity = now
        playQueue.offer(pcm)
    }

    private fun teardownAudio() {
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        playQueue.clear()
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        speakingUntil = 0
        pending.clear()
    }

    companion object {
        /** (model, config tier) that last completed setup: skips failing attempts next time. */
        @Volatile private var lastGood: Pair<String, Int>? = null
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** Tried in order when Verify hasn't listed the key's Live models yet. */
        val FALLBACK_LIVE_MODELS = listOf(
            "gemini-2.5-flash-native-audio-preview-09-2025",
            "gemini-live-2.5-flash-preview",
            "gemini-2.0-flash-live-001",
        )
        val VOICES = listOf("Charon", "Puck", "Kore", "Fenrir", "Aoede", "Orus")
    }
}
