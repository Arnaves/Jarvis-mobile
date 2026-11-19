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

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { unlockSignal++ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
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
            JarvisTheme(HudPalettes.byId(themeId)) {
                val sys by remember { app.monitor.flow() }.collectAsStateWithLifecycle(SystemSnapshot())
                val mode by app.assistant.mode.collectAsStateWithLifecycle()
                val voice by app.assistant.voiceState.collectAsStateWithLifecycle()
                val messages by app.assistant.messages.collectAsStateWithLifecycle()
                val hf by app.handsFree.state.collectAsStateWithLifecycle()
                val seconds by app.prefs.showSeconds.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    HudBackground()
                    LockScreen(
                        sys = sys,
                        mode = mode,
                        voice = voice,
                        lastMessage = messages.lastOrNull { !it.fromUser },
                        handsFree = hf != HandsFree.State.OFF,
                        showSeconds = seconds,
                        unlockSignal = unlockSignal,
                        onMic = { startListening() },
                        onUnlocked = { dismiss() },
                    )
                }
            }
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) app.handsFree.manualListen()
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
        const val EXTRA_FROM_WAKE = "from_wake"
    }
}
