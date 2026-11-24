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
    /** THINK brain: the model's visible reasoning. */
    val thoughts: String = "",
    /** Which brain answered (CHAT/THINK/CODE/VISION), for cloud replies. */
    val brain: String? = null,
) {
    enum class Source { USER, SKILL, NEURAL, SYSTEM, WEB, CLOUD }
}

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

sealed interface AssistantEvent {
    data class RequestPermission(val permission: String) : AssistantEvent
    data object LeaveLauncher : AssistantEvent
    /** Ask the UI to take a photo and hand it to [Assistant.askVision] with [prompt]. */
    data class CaptureImage(val prompt: String) : AssistantEvent
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
    private val briefing: Briefing,
    private val gemini: GeminiClient,
) {
    private val geminiHistory = ArrayList<GeminiClient.Turn>()
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
        skills.screen.onFailure = { msg -> post(msg, ChatMessage.Source.SYSTEM) }
        skills.whatsAppFlow.announce = { msg -> post(msg, ChatMessage.Source.SKILL) }
        skills.mediaSkills.speak = { msg -> if (msg.length <= 2) speaker.speak(msg) else post(msg, ChatMessage.Source.SYSTEM) }
        skills.setLlmReadyProvider { llm.isReady || gemini.available }
    }

    fun greet() {
        if (_messages.value.isNotEmpty()) return
        val core = when {
            gemini.available -> "Gemini uplink online."
            llm.isReady -> "Neural core online."
            else -> "Command systems online."
        }
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
        geminiHistory.clear()
    }

    /** Runs a deferred task's command: no one is around to confirm, so act directly. */
    fun runTask(command: String) {
        skills.whatsAppFlow.forceDirect = true
        try { submit(command) } finally { skills.whatsAppFlow.forceDirect = false }
    }

    fun submit(raw: String) {
        val text = stripWake(raw.trim())
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
        val asyncReply: (suspend () -> Reply)? = booking?.let { req -> { concierge.handle(req) } }
            ?: (if (briefing.matches(text)) ({ Reply(briefing.compose()) }) else null)
            ?: tasks.meta(text)
        if (asyncReply != null) {
            generation = scope.launch {
                thinking.value = true
                val r = try { asyncReply() } finally { thinking.value = false }
                deliver(r)
            }
            return
        }

        visionIntent(text)?.let { return }

        val reply = runCatching { skills.handle(text) }.getOrNull()
        if (reply != null) {
            deliver(reply)
            return
        }
        val webQuery = if (prefs.webEnabled.value) web.classify(text) else null
        when {
            // Structured live data (weather, news, prices, currency) has fast dedicated sources.
            webQuery != null && webQuery !is WebKnowledge.Query.Fact -> generation = scope.launch { askWeb(webQuery) }
            // Gemini answers everything else; facts and current events are grounded with Google Search.
            gemini.available -> generation = scope.launch {
                askGemini(text, grounded = webQuery is WebKnowledge.Query.Fact || currentEvents.containsMatchIn(text.lowercase()))
            }
            webQuery != null && !llm.isReady -> generation = scope.launch { askWeb(webQuery) }
            llm.isReady -> generation = scope.launch {
                askNeural(text, (webQuery as? WebKnowledge.Query.Fact)?.subject)
            }
            // No neural core: still try to answer from the internet rather than refusing.
            prefs.webEnabled.value && web.online -> generation = scope.launch {
                askWeb(WebKnowledge.Query.Fact(text.trim().trimEnd('?', '.', '!'), text), allowBrowser = false)
            }
            else -> post(
                "That's beyond my offline command set, ${prefs.userTitle.value}. " +
                    if (gemini.state.value.hasKey) "I need an internet connection for Gemini to answer that."
                    else "Add your Gemini API key in Settings and I can answer anything.",
                ChatMessage.Source.SYSTEM,
            )
        }
    }

    private suspend fun askWeb(q: WebKnowledge.Query, allowBrowser: Boolean = true) {
        val id = ids.incrementAndGet()
        add(ChatMessage(id, false, "", streaming = true, source = ChatMessage.Source.WEB))
        thinking.value = true
        val a = try {
            web.answer(q, prefs.userTitle.value)
        } finally {
            thinking.value = false
        }
        val shown = if (!allowBrowser && a.fallbackSearch != null)
            "I don't have an answer for that yet, ${prefs.userTitle.value}. Add your Gemini API key in Settings for full conversation, or say \"search the web for …\"."
        else a.text
        update(id, shown, streaming = false)
        a.permission?.let { _events.tryEmit(AssistantEvent.RequestPermission(it)) }
        if (prefs.voiceReplies.value) speaker.speak(shown)
        if (allowBrowser) a.fallbackSearch?.let { term ->
            skills.webSearch(term)
            _events.tryEmit(AssistantEvent.LeaveLauncher)
        }
    }

    /** True when JARVIS is neither thinking, listening nor talking. */
    val isIdle: Boolean get() = mode.value == CoreMode.IDLE

    /** "Hey Friday, open camera" -> "open camera" (the default "jarvis" is also handled downstream). */
    private fun stripWake(t: String): String {
        val w = prefs.wakeWord.value
        if (w == com.jarvis.launcher.data.Prefs.DEFAULT_WAKE) return t
        val p = w.split(' ').joinToString("\\s+") { Regex.escape(it) }
        return t.replace(Regex("^(?:(?:hey|ok|okay|hi|hello)\\s+)?$p[,.!]?\\s*", RegexOption.IGNORE_CASE), "").ifBlank { t }
    }

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

    private val currentEvents = Regex("\\b(today|tonight|latest|current|currently|now|news|score|won|winner|price|stock|election|this week|this year|recent|happening)\\b")

    private suspend fun askGemini(text: String, grounded: Boolean, image: ByteArray? = null) {
        val id = ids.incrementAndGet()
        val brain = Brain.route(text, image != null, Brain.of(prefs.brainMode.value))
        add(ChatMessage(id, false, "", streaming = true, source = ChatMessage.Source.CLOUD, brain = brain.name))
        thinking.value = true
        geminiHistory += GeminiClient.Turn(true, text, image)
        while (geminiHistory.size > 20) geminiHistory.removeAt(0)
        val sb = StringBuilder()
        var failed: Throwable? = null
        try {
            gemini.stream(systemInstruction(cloud = true, brain = brain), geminiHistory.toList(), grounded && brain != Brain.CODE, think = brain == Brain.THINK)
                .collect { delta ->
                    sb.append(delta)
                    updateSplit(id, sb.toString(), streaming = true)
                }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                update(id, visible(sb.toString()).ifBlank { "Cancelled." }, streaming = false)
                thinking.value = false
                throw t
            }
            failed = t
        }
        thinking.value = false
        if (failed != null && sb.isEmpty()) {
            geminiHistory.removeLastOrNull()
            // Uplink failed: fall back to the on-device core if there is one.
            if (llm.isReady) {
                _messages.value = _messages.value.filterNot { it.id == id }
                askNeural(text)
                return
            }
            // Factual question: answer from the web instead (Wikipedia / DuckDuckGo) rather than failing.
            if (grounded && prefs.webEnabled.value && web.online) {
                _messages.value = _messages.value.filterNot { it.id == id }
                askWeb(WebKnowledge.Query.Fact(text.trim().trimEnd('?', '.', '!'), text), allowBrowser = false)
                return
            }
            val reason = (failed.message ?: "unknown error").trimEnd('.')
            val msg = "Gemini uplink failed: $reason. " +
                if (reason.contains("quota", true)) "Free-tier limits reset over time; I'll keep switching to models with quota left."
                else "Check your connection or key in Settings."
            update(id, msg, streaming = false)
            if (prefs.voiceReplies.value) speaker.speak(msg)
            return
        }
        val full = sb.toString()
        val (_, answerRaw) = Brain.splitThoughts(full)
        // Don't keep image bytes in the running history.
        if (image != null) geminiHistory[geminiHistory.lastIndex] = GeminiClient.Turn(true, "$text [image shared]")
        geminiHistory += GeminiClient.Turn(false, answerRaw)
        updateSplit(id, full, streaming = false)
        val answer = visible(answerRaw).ifBlank { "Done." }
        val action = Regex("ACTION:\\s*(.+)", RegexOption.IGNORE_CASE).find(answerRaw)?.groupValues?.get(1)?.trim()
        val actionReply = action?.let { runCatching { skills.handle(it) }.getOrNull() }
        if (actionReply != null) deliver(actionReply)
        else if (prefs.voiceReplies.value) speaker.speak(Brain.speakable(answer))
    }

    private fun updateSplit(id: Long, raw: String, streaming: Boolean) {
        val (thoughts, answer) = Brain.splitThoughts(raw)
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = visible(answer), thoughts = thoughts, streaming = streaming) else it
        }
    }

    // ---------------------------------------------------------------- vision

    private val screenAsk = Regex("(what('s| is) on (my|the|this) screen|analy[sz]e (my |the |this )?screen|explain (this|my|the) screen|summari[sz]e (this|the) (screen|page|article)|deep analy[sz]e|look at (my|the) screen|what am i looking at on (the )?screen|help me with this screen)")
    private val cameraAsk = Regex("^(what('s| is) this|look at this|identify this|what am i looking at|what do you see|scan this|read this( label| text| sign)?|what is this (thing|object|plant|food)|translate this sign)")

    /** Starts a VISION request if [text] asks about the screen or the camera. Returns non-null if handled. */
    private fun visionIntent(text: String): Unit? {
        val q = text.lowercase().replace(Regex("^(hey |ok |okay )?jarvis[,.!]?\\s*"), "").trim().trimEnd('?', '.', '!')
        val screen = screenAsk.containsMatchIn(q)
        val camera = !screen && cameraAsk.containsMatchIn(q)
        if (!screen && !camera) return null
        if (!gemini.available) {
            post("Vision needs the Gemini brain, ${prefs.userTitle.value}. Add your API key in Settings.", ChatMessage.Source.SYSTEM)
            return Unit
        }
        if (camera) {
            _events.tryEmit(AssistantEvent.CaptureImage(text))
            return Unit
        }
        val svc = com.jarvis.launcher.service.JarvisAccessibility.instance
        generation = scope.launch {
            val shot = if (android.os.Build.VERSION.SDK_INT >= 30) svc?.screenshot() else null
            if (shot != null) askVision(text, Vision.jpeg(shot))
            else {
                val textOnScreen = svc?.readScreen(4000).orEmpty()
                if (textOnScreen.isBlank()) post("Enable JARVIS screen control in Settings so I can see the screen.", ChatMessage.Source.SYSTEM)
                else askGemini("$text\n\n[Screen text]: $textOnScreen", grounded = false)
            }
        }
        return Unit
    }

    /** Ask the VISION brain about [jpeg] (camera photo, gallery image or screenshot). */
    fun askVision(prompt: String, jpeg: ByteArray) {
        if (!gemini.available) {
            post("Vision needs the Gemini brain. Add your API key in Settings.", ChatMessage.Source.SYSTEM); return
        }
        add(ChatMessage(ids.incrementAndGet(), true, "📷 $prompt", source = ChatMessage.Source.USER))
        generation?.cancel()
        generation = scope.launch { askGemini(prompt, grounded = false, image = jpeg) }
    }

    /** Persona, phone-control protocol, the user's notes and live device context. */
    private fun systemInstruction(cloud: Boolean, brain: Brain = Brain.CHAT): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val s = runCatching { monitor.snapshot() }.getOrNull()
        val title = prefs.userTitle.value
        val notes = prefs.notes.value.takeLast(25)
        return buildString {
            append("You are JARVIS, a calm, witty and impeccably polite British AI assistant built into the user's Android phone launcher. ")
            append("Address the user as \"$title\". Replies are spoken aloud: keep them short and conversational (under 80 words), ")
            append("no markdown, no bullet lists, no URLs, unless the user asks for detail. ")
            if (cloud) append("You have Google Search for current information; use it for news, facts, sports, prices and anything time-sensitive. ")
            when (brain) {
                Brain.THINK -> append("THINK mode: reason step by step, then give a clear, concise conclusion. ")
                Brain.CODE -> append("CODE mode: give correct, complete code in fenced code blocks with a one-line explanation; the word limit does not apply to code. ")
                Brain.VISION -> append("VISION mode: an image is attached (camera photo or a screenshot of the user's phone). Answer about what is in it; read any text precisely. ")
                Brain.CHAT -> {}
            }
            append("If the user wants the phone to do something, add a final line exactly like `ACTION: <command>` using one of: ")
            append("open <app name> | set a timer for <n> minutes | set an alarm for <hh:mm> | call <contact> | text <contact> saying <message> | ")
            append("send a whatsapp to <contact> saying <message> | navigate to <place> | flashlight on | flashlight off | play music | pause music | ")
            append("volume up | volume down | remember <fact> | note for <today/tomorrow/weekday> <text> | ")
            append("read the screen | tap <label> | type <text> | scroll down | scroll up | go back | go home | open notifications | lock the phone | take a screenshot. ")
            append("Only use ACTION when the user clearly asked for that action. ")
            append("Reply in the language the user speaks; if they mix Hindi and English in Roman letters (Hinglish), reply the same way.\n")
            append("Current time: $now. Battery: ${s?.batteryPct ?: "?"}%${if (s?.charging == true) " (charging)" else ""}.\n")
            if (notes.isNotEmpty()) {
                append("The user's saved notes (use them when relevant, e.g. if asked what's planned or what they told you):\n")
                notes.forEach { n -> append("- ${n.forDay?.let { "[for ${NoteDates.label(it)}] " } ?: ""}${n.text}\n") }
            }
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
            if (memories.isNotEmpty()) append("The user's notes (use them when relevant, e.g. if asked what's planned): ${memories.joinToString("; ")}.\n")
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
