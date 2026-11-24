package com.jarvis.launcher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jarvis.launcher.ai.AssistantEvent
import com.jarvis.launcher.ai.HandsFree
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.components.HudBackground
import com.jarvis.launcher.ui.screens.LockScreen
import com.jarvis.launcher.ui.theme.HudPalettes
import com.jarvis.launcher.ui.theme.JarvisTheme
import kotlinx.coroutines.launch

/**
 * JARVIS lock screen, drawn over the system keyguard. The system lock (PIN, pattern,
 * fingerprint) still protects the device: swiping up hands over to it.
 */
class LockScreenActivity : ComponentActivity() {
    private val app get() = application as JarvisApp
    private var unlockSignal by androidx.compose.runtime.mutableIntStateOf(0)
    private var surge by androidx.compose.runtime.mutableStateOf(false)
    private var reason = Reason.ARM
    private var showCount by androidx.compose.runtime.mutableIntStateOf(0)

    enum class Reason { ARM, WAKE_WORD, CHARGER }

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Unlocked behind us (fingerprint/face): play the exit if visible, otherwise just vanish.
            if (visible) unlockSignal++ else finishQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            // Never light the display ourselves: the activity is pre-armed when the screen
            // turns off, so turning it on here would undo the sleep timer.
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        readIntent(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        ContextCompat.registerReceiver(this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            app.assistant.events.collect { e ->
                // A command wants to open something: ask the system to unlock first.
                if (e is AssistantEvent.LeaveLauncher) dismiss()
            }
        }

        setContent {
            val themeId by app.prefs.theme.collectAsState()
            val coreStyle by app.prefs.coreStyle.collectAsState()
            JarvisTheme(HudPalettes.byId(themeId)) {
              androidx.compose.runtime.CompositionLocalProvider(com.jarvis.launcher.ui.components.LocalCoreStyle provides coreStyle) {
                val sys by remember { app.monitor.flow() }.collectAsStateWithLifecycle(SystemSnapshot())
                val mode by app.assistant.mode.collectAsStateWithLifecycle()
                val voice by app.assistant.voiceState.collectAsStateWithLifecycle()
                val messages by app.assistant.messages.collectAsStateWithLifecycle()
                val hf by app.handsFree.state.collectAsStateWithLifecycle()
                val seconds by app.prefs.showSeconds.collectAsStateWithLifecycle()
                val wake by app.prefs.wakeWord.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    HudBackground()
                    LockScreen(
                        sys = sys,
                        mode = mode,
                        voice = voice,
                        lastMessage = messages.lastOrNull { !it.fromUser },
                        handsFree = hf != HandsFree.State.OFF,
                        wakeWord = wake,
                        showSeconds = seconds,
                        unlockSignal = unlockSignal,
                        surge = surge,
                        introKey = showCount,
                        onSurgeDone = { surge = false },
                        onUnlocked = { dismiss() },
                    )
                }
              }
            }
        }
    }

    private fun readIntent(i: Intent?) {
        reason = runCatching { Reason.valueOf(i?.getStringExtra(EXTRA_REASON) ?: "ARM") }.getOrDefault(Reason.ARM)
        // Only play the plug-in animation if the screen is already on (we never wake it ourselves).
        if (reason == Reason.CHARGER && getSystemService(android.os.PowerManager::class.java).isInteractive) surge = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        // Never stand between the user and their phone once it's already unlocked
        // (e.g. lock delay not elapsed, or we were launched late after biometric unlock).
        if (pm.isInteractive && !km.isKeyguardLocked && reason == Reason.ARM) { finishQuietly(); return }
        visible = true
        app.uiVisible.value = true
        if (pm.isInteractive) showCount++
    }

    override fun onStop() {
        visible = false
        app.uiVisible.value = false
        super.onStop()
    }

    private fun dismiss() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { finishQuietly(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finishQuietly()
            override fun onDismissCancelled() { unlockSignal = 0; recreate() }
            override fun onDismissError() = finishQuietly()
        })
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresent) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        @Volatile var visible = false
    }
}
