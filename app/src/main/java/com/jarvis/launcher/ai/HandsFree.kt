package com.jarvis.launcher.ai

import android.content.Context
import com.jarvis.launcher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hands-free voice loop:
 *   SLEEPING  - wake engine listens for "Hey JARVIS"
 *   AWAKE     - JARVIS answered "Yes, sir?" and listens for a command (5 s window)
 *   BUSY      - executing / speaking the reply
 * and then straight back to SLEEPING. Manual core taps borrow the mic and hand it back.
 */
class HandsFree(
    private val context: Context,
    private val voice: VoiceInput,
    private val speaker: Speaker,
    private val assistant: Assistant,
    private val prefs: Prefs,
    val models: WakeModelStore,
) {
    enum class State { OFF, STARTING, SLEEPING, AWAKE, BUSY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _engineLabel = MutableStateFlow<String?>(null)
    val engineLabel: StateFlow<String?> = _engineLabel.asStateFlow()

    private var engine: WakeEngine? = null
    private var vosk: VoskWakeEngine? = null
    private var enabled = false
    private var job: Job? = null

    /** Called when the wake word fires, e.g. so the service can light up the lock screen. */
    var onWake: (() -> Unit)? = null

    val isEnabled: Boolean get() = enabled

    fun enable() {
        if (enabled) return
        enabled = true
        sleep()
    }

    fun disable() {
        enabled = false
        job?.cancel()
        engine?.stop()
        voice.stop()
        _state.value = State.OFF
    }

    /** Re-select the engine (e.g. after the Vosk model finished downloading). */
    fun reload() {
        if (!enabled) return
        engine?.stop()
        engine = null
        sleep()
    }

    private fun sleep() {
        if (!enabled) { _state.value = State.OFF; return }
        job?.cancel()
        _state.value = State.STARTING
        job = scope.launch {
            val e = pickEngine()
            engine = e
            _engineLabel.value = e.label
            if (!enabled) return@launch
            _state.value = State.SLEEPING
            e.start { remainder -> wake(remainder) }
        }
    }

    private suspend fun pickEngine(): WakeEngine {
        if (!models.ready) models.installFromAssets()
        if (models.ready) {
            val v = vosk ?: VoskWakeEngine(models.dir).also { vosk = it }
            if (v.prepare()) return v
        }
        return engine as? RecognizerLoopWakeEngine ?: RecognizerLoopWakeEngine(context)
    }

    private fun wake(remainder: String?) {
        if (!enabled) return
        engine?.stop()
        onWake?.invoke()
        if (!remainder.isNullOrBlank()) {
            run(remainder)
            return
        }
        _state.value = State.AWAKE
        speaker.speak("Yes, ${prefs.userTitle.value}?") { listenForCommand() }
    }

    private fun listenForCommand() {
        if (!enabled) return
        _state.value = State.AWAKE
        voice.start(
            onResult = { text -> run(text) },
            startTimeoutMs = COMMAND_WINDOW_MS,
            quiet = true,
            onEnd = { sleep() },
        )
    }

    private fun run(command: String) {
        _state.value = State.BUSY
        assistant.submit(command)
        job?.cancel()
        job = scope.launch {
            waitUntilIdle()
            sleep()
        }
    }

    /** Waits until JARVIS has been idle (not thinking or talking) for ~0.75 s, capped at 90 s. */
    private suspend fun waitUntilIdle() {
        var calm = 0
        var waited = 0L
        delay(400)
        while (calm < 3 && waited < 90_000) {
            delay(250); waited += 250
            calm = if (assistant.isIdle) calm + 1 else 0
        }
    }

    /** Tap-to-talk. Pauses the wake engine, listens once, then resumes hands-free mode. */
    fun manualListen() {
        if (!enabled) { assistant.listen(); return }
        job?.cancel()
        engine?.stop()
        speaker.stop()
        _state.value = State.AWAKE
        voice.start(
            onResult = { text -> run(text) },
            onEnd = { sleep() },
        )
    }

    companion object {
        const val COMMAND_WINDOW_MS = 5_000L
    }
}
