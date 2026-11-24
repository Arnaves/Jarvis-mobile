package com.jarvis.launcher

import android.app.Application
import com.jarvis.launcher.ai.Assistant
import com.jarvis.launcher.ai.HandsFree
import com.jarvis.launcher.ai.WakeModelStore
import com.jarvis.launcher.ai.WebKnowledge
import com.jarvis.launcher.ai.LocalLlm
import com.jarvis.launcher.ai.Skills
import com.jarvis.launcher.ai.Speaker
import com.jarvis.launcher.ai.VoiceInput
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.data.Prefs
import com.jarvis.launcher.system.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class JarvisApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var prefs: Prefs; private set
    lateinit var apps: AppRepository; private set
    lateinit var monitor: SystemMonitor; private set
    lateinit var llm: LocalLlm; private set
    lateinit var speaker: Speaker; private set
    lateinit var voice: VoiceInput; private set
    lateinit var assistant: Assistant; private set
    lateinit var web: WebKnowledge; private set
    lateinit var handsFree: HandsFree; private set
    lateinit var tasks: com.jarvis.launcher.ai.TaskEngine; private set
    lateinit var gemini: com.jarvis.launcher.ai.GeminiClient; private set

    /** Fires when a charger is connected (UI plays the power-surge animation). */
    val powerSurge = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) { powerSurge.tryEmit(Unit) }
            },
            android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        prefs = Prefs(this)
        apps = AppRepository(this)
        monitor = SystemMonitor(this)
        llm = LocalLlm(this)
        speaker = Speaker(this)
        voice = VoiceInput(this) { prefs.speechLanguage.value }
        val contacts = com.jarvis.launcher.ai.Contacts(this)
        val skills = Skills(this, apps, prefs, monitor, contacts)
        web = WebKnowledge(this)
        tasks = com.jarvis.launcher.ai.TaskEngine(this, prefs)
        val concierge = com.jarvis.launcher.ai.Concierge(this, prefs, contacts, apps)
        val briefing = com.jarvis.launcher.ai.Briefing(prefs, tasks, monitor, web)
        gemini = com.jarvis.launcher.ai.GeminiClient(this)
        assistant = Assistant(skills, llm, voice, speaker, prefs, monitor, web, tasks, concierge, briefing, gemini)
        tasks.executor = { cmd -> assistant.runTask(cmd) }
        tasks.speak = { text -> if (prefs.voiceReplies.value) speaker.speak(text) }
        tasks.rearm()
        appScope.launch { prefs.speechLanguage.collect { kotlinx.coroutines.delay(1500); speaker.setLanguage(it) } }
        handsFree = HandsFree(this, voice, speaker, assistant, prefs, WakeModelStore(this))

        // Bring the neural core online in the background if a model is installed.
        val path = prefs.modelPath.value ?: llm.installedModels().firstOrNull()?.absolutePath
        if (path != null && File(path).exists()) {
            prefs.setModelPath(path)
            appScope.launch { llm.load(path, prefs.useGpu.value) }
        }
    }
}
