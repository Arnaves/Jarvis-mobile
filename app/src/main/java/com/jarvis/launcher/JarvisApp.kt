package com.jarvis.launcher

import android.app.Application
import com.jarvis.launcher.ai.Assistant
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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        apps = AppRepository(this)
        monitor = SystemMonitor(this)
        llm = LocalLlm(this)
        speaker = Speaker(this)
        voice = VoiceInput(this)
        val skills = Skills(this, apps, prefs, monitor)
        assistant = Assistant(skills, llm, voice, speaker, prefs, monitor)

        // Bring the neural core online in the background if a model is installed.
        val path = prefs.modelPath.value ?: llm.installedModels().firstOrNull()?.absolutePath
        if (path != null && File(path).exists()) {
            prefs.setModelPath(path)
            appScope.launch { llm.load(path, prefs.useGpu.value) }
        }
    }
}
