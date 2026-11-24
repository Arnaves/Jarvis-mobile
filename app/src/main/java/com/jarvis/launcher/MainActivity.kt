package com.jarvis.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jarvis.launcher.ai.AssistantEvent
import com.jarvis.launcher.data.AppEntry
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.components.HudBackground
import com.jarvis.launcher.ui.screens.AppDrawer
import com.jarvis.launcher.ui.screens.BootSequence
import com.jarvis.launcher.ui.screens.ConsoleScreen
import com.jarvis.launcher.ui.screens.HomeScreen
import com.jarvis.launcher.ui.screens.OnboardingCard
import com.jarvis.launcher.ui.screens.SettingsScreen
import com.jarvis.launcher.ui.theme.HudPalettes
import com.jarvis.launcher.ui.theme.JarvisTheme
import kotlinx.coroutines.launch

enum class Screen { HOME, DRAWER, CONSOLE, SETTINGS }

class MainActivity : ComponentActivity() {
    private val app get() = application as JarvisApp

    private var screen by mutableStateOf(Screen.HOME)
    private var isDefault by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var pendingListen = false
    private var booted = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        micGranted = granted
        if (granted && pendingListen) app.handsFree.manualListen()
        com.jarvis.launcher.service.JarvisService.sync(this)
        pendingListen = false
    }
    private val genericPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toast("Access granted.")
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            toast("Importing neural core… this can take a minute.")
            runCatching { app.llm.importFrom(uri) }
                .onSuccess { path ->
                    app.prefs.setModelPath(path)
                    app.llm.load(path, app.prefs.useGpu.value)
                }
                .onFailure { toast("Import failed: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- VISION (camera / gallery)
    private var visionPrompt = "What is this?"
    private var photoUri: android.net.Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) analyze(uri, visionPrompt)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analyze(uri, visionPrompt)
    }

    private fun analyze(uri: android.net.Uri, prompt: String) {
        screen = Screen.CONSOLE
        lifecycleScope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.jarvis.launcher.ai.Vision.fromUri(this@MainActivity, uri) }
            if (jpeg == null) toast("Couldn't read that image") else app.assistant.askVision(prompt, jpeg)
        }
    }

    fun capturePhoto(prompt: String = "What is this?") {
        visionPrompt = prompt
        val f = java.io.File(cacheDir, "vision/shot.jpg").apply { parentFile?.mkdirs(); delete() }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        photoUri = uri
        runCatching { takePhoto.launch(uri) }.onFailure { toast("No camera app available") }
    }

    fun pickPhoto(prompt: String = "Describe this image.") {
        visionPrompt = prompt
        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        booted = savedInstanceState?.getBoolean("booted") ?: false
        handleIntent(intent)

        lifecycleScope.launch {
            app.assistant.events.collect { e ->
                when (e) {
                    is AssistantEvent.RequestPermission -> genericPermission.launch(e.permission)
                    AssistantEvent.LeaveLauncher -> screen = Screen.HOME
                    is AssistantEvent.CaptureImage -> {
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) capturePhoto(e.prompt)
                        else runCatching {
                            // Asked hands-free from another app: bring JARVIS forward to take the photo.
                            startActivity(Intent(this@MainActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                .putExtra(EXTRA_CAPTURE, e.prompt))
                        }
                    }
                }
            }
        }

        setContent {
            val themeId by app.prefs.theme.collectAsState()
            JarvisTheme(HudPalettes.byId(themeId)) { Root() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val apps by app.apps.apps.collectAsStateWithLifecycle()
        val pinned by app.prefs.pinned.collectAsStateWithLifecycle()
        val sys by remember { app.monitor.flow() }.collectAsStateWithLifecycle(SystemSnapshot())
        val mode by app.assistant.mode.collectAsStateWithLifecycle()
        val voice by app.assistant.voiceState.collectAsStateWithLifecycle()
        val messages by app.assistant.messages.collectAsStateWithLifecycle()
        val llm by app.llm.state.collectAsStateWithLifecycle()
        val showSeconds by app.prefs.showSeconds.collectAsStateWithLifecycle()
        val onboarded by app.prefs.onboarded.collectAsStateWithLifecycle()
        val handsFree by app.handsFree.state.collectAsStateWithLifecycle()
        val gem by app.gemini.state.collectAsStateWithLifecycle()
        val wake by app.prefs.wakeWord.collectAsStateWithLifecycle()
        var bootDone by remember { mutableStateOf(booted) }
        var surge by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            app.powerSurge.collect {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) surge = true
            }
        }

        val dock: List<AppEntry> = remember(apps, pinned) {
            if (pinned.isEmpty()) app.apps.defaultDock() else pinned.mapNotNull { k -> apps.firstOrNull { it.key == k } }
        }

        LaunchedEffect(bootDone) { if (bootDone) { booted = true; app.assistant.greet() } }

        BackHandler(enabled = screen != Screen.HOME) {
            if (screen == Screen.CONSOLE) app.assistant.stopListening()
            screen = Screen.HOME
        }

        Box(Modifier.fillMaxSize()) {
            HudBackground(intensity = if (screen == Screen.HOME) 1f else 0.6f)

            HomeScreen(
                repo = app.apps,
                dock = dock,
                sys = sys,
                mode = mode,
                voice = voice,
                llm = llm,
                lastMessage = messages.lastOrNull { !it.fromUser },
                showSeconds = showSeconds,
                handsFree = handsFree != com.jarvis.launcher.ai.HandsFree.State.OFF,
                cloudOnline = gem.hasKey && gem.enabled && sys.network != "OFFLINE",
                wakeWord = wake,
                onCoreTap = { startListening() },
                onCoreHold = { screen = Screen.CONSOLE },
                onOpenDrawer = { screen = Screen.DRAWER },
                onOpenConsole = { screen = Screen.CONSOLE },
                onOpenSettings = { screen = Screen.SETTINGS },
                onPullDown = { expandNotifications() },
                onLaunch = { launch(it) },
                onDockLongPress = { togglePin(it) },
            )

            Overlay(screen == Screen.DRAWER) {
                AppDrawer(
                    repo = app.apps,
                    apps = apps,
                    pinned = if (pinned.isEmpty()) dock.map { it.key } else pinned,
                    onLaunch = { launch(it) },
                    onTogglePin = { togglePin(it) },
                    onAskJarvis = { q -> screen = Screen.CONSOLE; app.assistant.submit(q) },
                    onClose = { screen = Screen.HOME },
                )
            }
            Overlay(screen == Screen.CONSOLE) {
                ConsoleScreen(
                    messages = messages,
                    mode = mode,
                    voice = voice,
                    llm = llm,
                    cloudOnline = gem.hasKey && gem.enabled && sys.network != "OFFLINE",
                    onSend = { app.assistant.submit(it) },
                    onMic = { startListening() },
                    onStop = { app.assistant.cancel() },
                    onClear = { app.assistant.clearConversation() },
                    onCamera = { capturePhoto() },
                    onGallery = { pickPhoto() },
                    onClose = { screen = Screen.HOME },
                )
            }
            Overlay(screen == Screen.SETTINGS) {
                SettingsScreen(
                    app = app,
                    isDefaultLauncher = isDefault,
                    onSetDefault = { openHomeSettings() },
                    onImportModel = { importModel.launch(arrayOf("*/*")) },
                    onRequestPermission = { p ->
                        if (p == Manifest.permission.RECORD_AUDIO) requestMic() else genericPermission.launch(p)
                    },
                    onClose = { screen = Screen.HOME },
                )
            }

            if (bootDone && !onboarded) {
                OnboardingCard(
                    isDefault = isDefault,
                    micGranted = micGranted,
                    onSetDefault = { openHomeSettings() },
                    onGrantMic = { requestMic() },
                    onOpenCore = { app.prefs.setOnboarded(); screen = Screen.SETTINGS },
                    onDone = { app.prefs.setOnboarded() },
                )
            }
            if (surge) com.jarvis.launcher.ui.components.PowerSurge(sys, onDone = { surge = false })
            if (!bootDone) BootSequence { bootDone = true }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Overlay(visible: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(280)) { it / 8 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 8 },
        ) { content() }
    }

    private fun launch(entry: AppEntry) {
        if (!app.apps.launch(entry)) toast("Unable to launch ${entry.label}")
        else screen = Screen.HOME
    }

    private fun togglePin(entry: AppEntry) {
        val prefs = app.prefs
        if (prefs.pinned.value.isEmpty()) prefs.setPinned(app.apps.defaultDock().map { it.key })
        val wasPinned = entry.key in prefs.pinned.value
        if (!wasPinned && prefs.pinned.value.size >= com.jarvis.launcher.data.Prefs.MAX_DOCK) {
            toast("Dock is full: oldest slot replaced")
        }
        prefs.togglePin(entry.key)
        toast(if (wasPinned) "${entry.label} removed from dock" else "${entry.label} pinned to dock")
    }

    /** Microphone, plus notifications (Android 13+) so the hands-free indicator is visible. */
    private fun requestMic() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        micPermission.launch(perms.toTypedArray())
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            app.handsFree.manualListen()
        } else {
            pendingListen = true
            requestMic()
        }
    }

    private fun openHomeSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess
        if (!ok) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    @Suppress("WrongConstant")
    private fun expandNotifications() {
        runCatching {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_CAPTURE)?.let { prompt ->
            intent.removeExtra(EXTRA_CAPTURE)
            window.decorView.post { capturePhoto(prompt) }
            return
        }
        // Tapped "Run now" on a deferred task notification (e.g. WhatsApp on arrival).
        intent?.getStringExtra(com.jarvis.launcher.ai.TaskEngine.EXTRA_RUN_COMMAND)?.let { cmd ->
            intent.removeExtra(com.jarvis.launcher.ai.TaskEngine.EXTRA_RUN_COMMAND)
            screen = Screen.CONSOLE
            window.decorView.post { app.assistant.runTask(cmd) }
            return
        }
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> {
                screen = Screen.CONSOLE
                window.decorView.post { startListening() }
            }
            Intent.ACTION_MAIN -> if (intent.hasCategory(Intent.CATEGORY_HOME)) {
                // Home pressed while already home: collapse everything.
                screen = Screen.HOME
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isDefault = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        com.jarvis.launcher.service.JarvisService.sync(this)
        app.tasks.syncGeofences()
    }

    override fun onStop() {
        super.onStop()
        // In hands-free mode the voice loop keeps running in the background service.
        if (!app.handsFree.isEnabled) app.assistant.stopListening()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("booted", booted)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CAPTURE = "vision_capture"
    }
}
