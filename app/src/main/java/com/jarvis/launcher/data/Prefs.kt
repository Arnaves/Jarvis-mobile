package com.jarvis.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** A note. [forDay] is the local start-of-day (ms) it is meant for, e.g. "for tomorrow". */
data class Note(val text: String, val createdAt: Long, val forDay: Long? = null)

/** Small reactive preference store. Everything stays on-device. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(readList(KEY_PINNED))
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    private val _notes = MutableStateFlow(readNotes())
    /** Notes / memories with timestamps and an optional day they are meant for. */
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    private val _memories = MutableStateFlow(_notes.value.map { it.text })
    /** Plain text of every note (used by the LLM persona and older call sites). */
    val memories: StateFlow<List<String>> = _memories.asStateFlow()

    private val _theme = MutableStateFlow(sp.getString(KEY_THEME, "arc")!!)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _voiceReplies = MutableStateFlow(sp.getBoolean(KEY_VOICE, true))
    val voiceReplies: StateFlow<Boolean> = _voiceReplies.asStateFlow()

    private val _userTitle = MutableStateFlow(sp.getString(KEY_TITLE, "sir")!!)
    val userTitle: StateFlow<String> = _userTitle.asStateFlow()

    private val _modelPath = MutableStateFlow(sp.getString(KEY_MODEL, null))
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()

    private val _useGpu = MutableStateFlow(sp.getBoolean(KEY_GPU, false))
    val useGpu: StateFlow<Boolean> = _useGpu.asStateFlow()

    private val _showSeconds = MutableStateFlow(sp.getBoolean(KEY_SECONDS, true))
    val showSeconds: StateFlow<Boolean> = _showSeconds.asStateFlow()

    private val _handsFree = MutableStateFlow(sp.getBoolean(KEY_HANDSFREE, false))
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    private val _lockScreen = MutableStateFlow(sp.getBoolean(KEY_LOCK, false))
    val lockScreen: StateFlow<Boolean> = _lockScreen.asStateFlow()

    private val _web = MutableStateFlow(sp.getBoolean(KEY_WEB, true))
    val webEnabled: StateFlow<Boolean> = _web.asStateFlow()

    fun setHandsFree(v: Boolean) { _handsFree.value = v; sp.edit().putBoolean(KEY_HANDSFREE, v).apply() }
    fun setLockScreen(v: Boolean) { _lockScreen.value = v; sp.edit().putBoolean(KEY_LOCK, v).apply() }
    fun setWebEnabled(v: Boolean) { _web.value = v; sp.edit().putBoolean(KEY_WEB, v).apply() }

    private val _wakeWord = MutableStateFlow(sp.getString("wake_word", DEFAULT_WAKE)!!)
    /** Lower-case wake phrase, 1–3 words (default "jarvis"). */
    val wakeWord: StateFlow<String> = _wakeWord.asStateFlow()
    fun setWakeWord(v: String) {
        val w = v.lowercase().replace(Regex("[^\\p{L} ]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { DEFAULT_WAKE }
        _wakeWord.value = w; sp.edit().putString("wake_word", w).apply()
    }

    private val _wakeSensitivity = MutableStateFlow(sp.getString("wake_sens", "NORMAL")!!)
    val wakeSensitivity: StateFlow<String> = _wakeSensitivity.asStateFlow()
    fun setWakeSensitivity(v: String) { _wakeSensitivity.value = v; sp.edit().putString("wake_sens", v).apply() }

    private val _directMessage = MutableStateFlow(sp.getBoolean("direct_msg", false))
    /** Send WhatsApp messages without the extra "send it" confirmation. */
    val directMessage: StateFlow<Boolean> = _directMessage.asStateFlow()
    fun setDirectMessage(v: Boolean) { _directMessage.value = v; sp.edit().putBoolean("direct_msg", v).apply() }

    private val _roman = MutableStateFlow(sp.getBoolean("roman", true))
    /** Write native-script messages in Roman letters (Hindi -> Hinglish). */
    val romanLetters: StateFlow<Boolean> = _roman.asStateFlow()
    fun setRomanLetters(v: Boolean) { _roman.value = v; sp.edit().putBoolean("roman", v).apply() }

    private val _brain = MutableStateFlow(sp.getString("brain", "AUTO")!!)
    /** "AUTO" lets the router pick CHAT/THINK/CODE; otherwise a fixed Brain name. */
    val brainMode: StateFlow<String> = _brain.asStateFlow()
    fun setBrainMode(v: String) { _brain.value = v; sp.edit().putString("brain", v).apply() }

    private val _ownerName = MutableStateFlow(sp.getString("owner", "")!!)
    val ownerName: StateFlow<String> = _ownerName.asStateFlow()
    fun setOwnerName(v: String) { _ownerName.value = v; sp.edit().putString("owner", v).apply() }

    private val _onboarded = MutableStateFlow(sp.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setPinned(list: List<String>) { _pinned.value = list; writeList(KEY_PINNED, list) }
    fun togglePin(key: String) {
        val cur = _pinned.value
        setPinned(if (key in cur) cur - key else (cur + key).takeLast(MAX_DOCK))
    }

    fun addMemory(text: String, forDay: Long? = null) {
        setNotes((_notes.value + Note(text, System.currentTimeMillis(), forDay)).takeLast(100))
    }
    fun removeNote(n: Note) = setNotes(_notes.value - n)
    fun clearMemories() = setNotes(emptyList())

    private fun setNotes(list: List<Note>) {
        _notes.value = list
        _memories.value = list.map { it.text }
        val arr = JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("t", it.text).put("at", it.createdAt).apply { it.forDay?.let { d -> put("day", d) } }) }
        sp.edit().putString(KEY_NOTES, arr.toString()).apply()
    }

    private fun readNotes(): List<Note> {
        sp.getString(KEY_NOTES, null)?.let { raw ->
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    Note(o.getString("t"), o.optLong("at"), if (o.has("day")) o.getLong("day") else null)
                }
            }.getOrDefault(emptyList())
        }
        // Migrate plain-string memories from older versions.
        return readList(KEY_MEMORIES).map { Note(it, 0L, null) }
    }

    fun setTheme(id: String) { _theme.value = id; sp.edit().putString(KEY_THEME, id).apply() }
    fun setVoiceReplies(v: Boolean) { _voiceReplies.value = v; sp.edit().putBoolean(KEY_VOICE, v).apply() }
    fun setUserTitle(v: String) { _userTitle.value = v; sp.edit().putString(KEY_TITLE, v).apply() }
    fun setModelPath(v: String?) { _modelPath.value = v; sp.edit().putString(KEY_MODEL, v).apply() }
    fun setUseGpu(v: Boolean) { _useGpu.value = v; sp.edit().putBoolean(KEY_GPU, v).apply() }
    fun setShowSeconds(v: Boolean) { _showSeconds.value = v; sp.edit().putBoolean(KEY_SECONDS, v).apply() }
    fun setOnboarded() { _onboarded.value = true; sp.edit().putBoolean(KEY_ONBOARDED, true).apply() }

    private fun readList(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, list: List<String>) {
        sp.edit().putString(key, JSONArray(list).toString()).apply()
    }

    companion object {
        const val MAX_DOCK = 5
        const val DEFAULT_WAKE = "jarvis"

        /** How the wake phrase is shown/said: "Hey Jarvis" for one word, the phrase itself otherwise. */
        fun wakeDisplay(w: String): String {
            val words = w.split(' ').filter { it.isNotBlank() }
            val cap = words.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            return if (words.size == 1) "Hey $cap" else cap
        }
        private const val KEY_PINNED = "pinned"
        private const val KEY_MEMORIES = "memories"
        private const val KEY_NOTES = "notes_v2"
        private const val KEY_THEME = "theme"
        private const val KEY_VOICE = "voice"
        private const val KEY_TITLE = "title"
        private const val KEY_MODEL = "model"
        private const val KEY_GPU = "gpu"
        private const val KEY_SECONDS = "seconds"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_HANDSFREE = "handsfree"
        private const val KEY_LOCK = "lockscreen"
        private const val KEY_WEB = "web"
    }
}
