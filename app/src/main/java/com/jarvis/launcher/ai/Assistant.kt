package com.jarvis.launcher.ai

import com.jarvis.launcher.data.Prefs
import com.jarvis.launcher.system.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val source: Source = Source.SKILL,
) {
    enum class Source { USER, SKILL, NEURAL, SYSTEM, WEB }
}

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

sealed interface AssistantEvent {
    data class RequestPermission(val permission: String) : AssistantEvent
    data object LeaveLauncher : AssistantEvent
}

class Assistant(
    private val skills: Skills,
    private val llm: LocalLlm,
    private val voice: VoiceInput,
    private val speaker: Speaker,
    private val prefs: Prefs,
    private val monitor: SystemMonitor,
    private val web: WebKnowledge,
    private val tasks: TaskEngine,
    private val concierge: Concierge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(0)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val thinking = MutableStateFlow(false)
    private var generation: Job? = null

    private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AssistantEvent> = _events.asSharedFlow()

    val mode: StateFlow<CoreMode> = combine(voice.state, thinking, speaker.speaking) { v, t, s ->
        when {
            v.listening -> CoreMode.LISTENING
            t -> CoreMode.THINKING
            s -> CoreMode.SPEAKING
            else -> CoreMode.IDLE
        }
    }.stateIn(scope, SharingStarted.Eagerly, CoreMode.IDLE)

    val voiceState get() = voice.state

    init {
        skills.setLlmReadyProvider { llm.isReady }
    }

    fun greet() {
        if (_messages.value.isNotEmpty()) return
        val core = if (llm.isReady) "Neural core online." else "Command systems online."
        post("${skills.timeGreeting()}, ${prefs.userTitle.value}. $core How can I help?", ChatMessage.Source.SYSTEM, speak = false)
    }

    fun listen() {
        speaker.stop()
        if (!voice.available) {
            post("Speech recognition isn't available on this device. You can type to me instead.", ChatMessage.Source.SYSTEM)
            return
        }
        voice.start(onResult = { text -> submit(text) })
    }

    fun stopListening() = voice.stop()

    fun cancel() {
        generation?.cancel()
        voice.stop()
        speaker.stop()
        thinking.value = false
    }

    fun clearConversation() {
        cancel()
        _messages.value = emptyList()
        llm.resetConversation()
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        voice.clearError()
        speaker.stop()
        generation?.cancel()
        add(ChatMessage(ids.incrementAndGet(), true, text, source = ChatMessage.Source.USER))

        // Bookings / appointments first: "for tomorrow morning" there is the booking time, not a trigger.
        val booking = concierge.classify(text)
        // Deferred tasks: "...when I reach office", "at 6 pm ...", "in 20 minutes ..."
        if (booking == null) TaskParser.parse(text)?.let { parsed ->
            deliver(tasks.schedule(parsed))
            return
        }
        // Places & task management, bookings (async)
        val asyncReply: (suspend () -> Reply)? = booking?.let { req -> { concierge.handle(req) } } ?: tasks.meta(text)
        if (asyncReply != null) {
            generation = scope.launch {
                thinking.value = true
                val r = try { asyncReply() } finally { thinking.value = false }
                deliver(r)
            }
            return
        }

        val reply = runCatching { skills.handle(text) }.getOrNull()
        if (reply != null) {
            deliver(reply)
            return
        }
        val webQuery = if (prefs.webEnabled.value) web.classify(text) else null
        when {
            // Live data (weather, news, prices) always comes from the network; facts too when no LLM.
            webQuery != null && (webQuery !is WebKnowledge.Query.Fact || !llm.isReady) ->
                generation = scope.launch { askWeb(webQuery) }
            llm.isReady -> generation = scope.launch {
                askNeural(text, (webQuery as? WebKnowledge.Query.Fact)?.subject)
            }
            else -> post(
                "That's beyond my offline command set, ${prefs.userTitle.value}. " +
                    if (prefs.webEnabled.value) "Install a neural core in Settings for open conversation."
                    else "Enable internet knowledge or install a neural core in Settings.",
                ChatMessage.Source.SYSTEM,
            )
        }
    }

    private suspend fun askWeb(q: WebKnowledge.Query) {
        val id = ids.incrementAndGet()
        add(ChatMessage(id, false, "", streaming = true, source = ChatMessage.Source.WEB))
        thinking.value = true
        val a = try {
            web.answer(q, prefs.userTitle.value)
        } finally {
            thinking.value = false
        }
        update(id, a.text, streaming = false)
        a.permission?.let { _events.tryEmit(AssistantEvent.RequestPermission(it)) }
        if (prefs.voiceReplies.value) speaker.speak(a.text)
        a.fallbackSearch?.let { term ->
            skills.webSearch(term)
            _events.tryEmit(AssistantEvent.LeaveLauncher)
        }
    }

    /** True when JARVIS is neither thinking, listening nor talking. */
    val isIdle: Boolean get() = mode.value == CoreMode.IDLE

    private fun deliver(reply: Reply) {
        post(reply.text, ChatMessage.Source.SKILL)
        reply.permission?.let { _events.tryEmit(AssistantEvent.RequestPermission(it)) }
        reply.action?.let { runCatching { it() } }
        if (reply.leavesLauncher) _events.tryEmit(AssistantEvent.LeaveLauncher)
    }

    private suspend fun askNeural(text: String, groundOn: String? = null) {
        val id = ids.incrementAndGet()
        add(ChatMessage(id, false, "", streaming = true, source = ChatMessage.Source.NEURAL))
        thinking.value = true
        val sb = StringBuilder()
        try {
            val snippet = if (groundOn != null) web.snippet(groundOn) else null
            val prompt = buildPrompt(text, snippet)
            llm.generate(prompt).collect { delta ->
                sb.append(delta)
                update(id, visible(sb.toString()), streaming = true)
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                update(id, visible(sb.toString()).ifBlank { "Cancelled." }, streaming = false)
                thinking.value = false
                throw t
            }
            if (sb.isEmpty()) sb.append("My neural core hiccupped: ${t.message ?: "unknown error"}. Context has been reset.")
        }
        thinking.value = false
        val full = sb.toString()
        val answer = visible(full).ifBlank { "Done." }
        update(id, answer, streaming = false)

        // Let the model drive device actions through the deterministic skill layer.
        val action = Regex("ACTION:\\s*(.+)", RegexOption.IGNORE_CASE).find(full)?.groupValues?.get(1)?.trim()
        val actionReply = action?.let { runCatching { skills.handle(it) }.getOrNull() }
        if (actionReply != null) {
            deliver(actionReply)
        } else if (prefs.voiceReplies.value) {
            speaker.speak(answer)
        }
    }

    private fun visible(s: String) = s
        .replace(Regex("ACTION:.*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<end_of_turn>|<eos>|<\\|im_end\\|>|<\\|end\\|>"), "")
        .trim()

    private fun buildPrompt(text: String, webInfo: String? = null): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val s = runCatching { monitor.snapshot() }.getOrNull()
        val live = webInfo?.let { "\n[Live web info: $it]" } ?: ""
        val ctx = "[Device context: $now; battery ${s?.batteryPct ?: "?"}%]$live"
        if (!llm.needsPreamble) return "$ctx\n$text"
        val memories = prefs.memories.value.takeLast(10)
        val title = prefs.userTitle.value
        return buildString {
            append("You are JARVIS, a calm, witty and impeccably polite British AI assistant running entirely offline on the user's Android phone. ")
            append("Address the user as \"$title\". Keep replies short and conversational (under 70 words) unless asked for detail. ")
            append("When a message includes [Live web info], base your answer on it; otherwise do not invent current events. ")
            append("If the user wants the phone to do something, add a final line exactly like `ACTION: <command>` using one of: ")
            append("open <app name> | set a timer for <n> minutes | set an alarm for <hh:mm> | call <contact> | text <contact> saying <message> | ")
            append("search for <query> | navigate to <place> | flashlight on | flashlight off | play music | pause music | volume up | volume down | remember <fact>.\n")
            if (memories.isNotEmpty()) append("Things the user asked you to remember: ${memories.joinToString("; ")}.\n")
            append(ctx).append('\n')
            append(text)
        }
    }

    private fun post(text: String, source: ChatMessage.Source, speak: Boolean = true) {
        add(ChatMessage(ids.incrementAndGet(), false, text, source = source))
        if (speak && prefs.voiceReplies.value) speaker.speak(text)
    }

    private fun add(m: ChatMessage) {
        _messages.value = (_messages.value + m).takeLast(60)
    }

    private fun update(id: Long, text: String, streaming: Boolean) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(text = text, streaming = streaming) else it }
    }
}
