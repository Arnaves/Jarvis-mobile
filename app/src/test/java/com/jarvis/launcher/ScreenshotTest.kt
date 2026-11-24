package com.jarvis.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.jarvis.launcher.ai.ChatMessage
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ai.CoreStatus
import com.jarvis.launcher.ai.LlmState
import com.jarvis.launcher.ai.VoiceState
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.components.HudBackground
import com.jarvis.launcher.ui.screens.BootSequence
import com.jarvis.launcher.ui.screens.ConsoleScreen
import com.jarvis.launcher.ui.screens.HomeScreen
import com.jarvis.launcher.ui.screens.SettingsScreen
import com.jarvis.launcher.ui.theme.HudPalettes
import com.jarvis.launcher.ui.theme.JarvisTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<JarvisApp>()

    private val sys = SystemSnapshot(
        batteryPct = 82, charging = true, batteryTempC = 31.4f, ramUsedPct = 57, ramTotalGb = 8f,
        storageUsedPct = 64, storageFreeGb = 41.7f, network = "WI-FI", uptime = "3d 04h 12m", nextAlarm = "Fri 07:00",
    )

    private fun shot(name: String, palette: String = "arc", ms: Long = 1500, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JarvisTheme(HudPalettes.byId(palette)) {
                Box(Modifier.fillMaxSize()) { HudBackground(); content() }
            }
        }
        rule.mainClock.advanceTimeBy(ms)
        rule.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Composable private fun home(mode: CoreMode, voice: VoiceState = VoiceState(), msg: String) = HomeScreen(
        repo = app.apps, dock = emptyList(), sys = sys, mode = mode, voice = voice,
        llm = LlmState(CoreStatus.READY, "gemma3-1b-it-int4.task", "Neural core online"),
        lastMessage = ChatMessage(1, false, msg), showSeconds = true,
        onCoreTap = {}, onCoreHold = {}, onOpenDrawer = {}, onOpenConsole = {}, onOpenSettings = {},
        onPullDown = {}, onLaunch = {}, onDockLongPress = {},
    )

    @Test fun homeIdle() = shot("home_idle") { home(CoreMode.IDLE, msg = "Good evening, sir. Neural core online. How can I help?") }

    @Test fun homeOrb() = shot("home_orb") {
        androidx.compose.runtime.CompositionLocalProvider(com.jarvis.launcher.ui.components.LocalCoreStyle provides "orb") {
            home(CoreMode.SPEAKING, msg = "Particle orb core: speaking.")
        }
    }

    @Test fun homeListening() = shot("home_listening") {
        home(CoreMode.LISTENING, VoiceState(listening = true, level = 0.8f, partial = "set a timer for ten minutes"), "…")
    }

    @Test fun homeRed() = shot("home_hotrod", palette = "mark") { home(CoreMode.SPEAKING, msg = "All systems nominal. Power 82% and charging.") }

    @Test fun console() = shot("console") {
        ConsoleScreen(
            messages = listOf(
                ChatMessage(1, false, "Good evening, sir. Neural core online. How can I help?", source = ChatMessage.Source.SYSTEM),
                ChatMessage(2, true, "Run a diagnostic", source = ChatMessage.Source.USER),
                ChatMessage(3, false, "All systems nominal. Power 82% and charging. Memory 57% utilised of 8.0 gigabytes. Storage 64% full.", source = ChatMessage.Source.SKILL),
                ChatMessage(4, true, "Give me a fun fact about black holes", source = ChatMessage.Source.USER),
                ChatMessage(5, false, "Certainly, sir. If you fell into a stellar-mass black hole, tidal forces would stretch you like spaghetti. Astrophysicists genuinely call it spaghettification.", source = ChatMessage.Source.NEURAL),
            ),
            mode = CoreMode.IDLE, voice = VoiceState(),
            llm = LlmState(CoreStatus.READY, "gemma3-1b-it-int4.task", "Neural core online"),
            onSend = {}, onMic = {}, onStop = {}, onClear = {}, onClose = {},
        )
    }

    @Test fun settings() = shot("settings") {
        SettingsScreen(app, isDefaultLauncher = false, onSetDefault = {}, onImportModel = {}, onRequestPermission = {}, onClose = {})
    }

    @Test fun lockScreen() = shot("lock_screen", ms = 2000) {
        com.jarvis.launcher.ui.screens.LockScreen(
            sys = sys, mode = CoreMode.IDLE, voice = VoiceState(),
            lastMessage = ChatMessage(1, false, "Currently 14 degrees and partly cloudy. Today's range is 9 to 17."),
            handsFree = true, showSeconds = true, unlockSignal = 0, onUnlocked = {},
        )
    }

    @Test fun boot() = shot("boot", ms = 1200) { BootSequence {} }
}
