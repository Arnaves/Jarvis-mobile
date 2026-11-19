package com.jarvis.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Small reactive preference store. Everything stays on-device. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(readList(KEY_PINNED))
    val pinned: StateFlow<List<String>> = _pinned.asStateFlow()

    private val _memories = MutableStateFlow(readList(KEY_MEMORIES))
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

    fun addMemory(text: String) {
        val list = (_memories.value + text).takeLast(50)
        _memories.value = list; writeList(KEY_MEMORIES, list)
    }
    fun clearMemories() { _memories.value = emptyList(); writeList(KEY_MEMORIES, emptyList()) }

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
        private const val KEY_PINNED = "pinned"
        private const val KEY_MEMORIES = "memories"
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
