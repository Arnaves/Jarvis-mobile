package com.jarvis.launcher.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.launcher.JarvisApp
import com.jarvis.launcher.LockScreenActivity
import com.jarvis.launcher.MainActivity
import com.jarvis.launcher.R

/**
 * Foreground service that keeps "Hey JARVIS" listening in the background and
 * raises the JARVIS lock screen whenever the display turns on.
 */
class JarvisService : Service() {
    private val app get() = application as JarvisApp

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!app.prefs.lockScreen.value) return
            when (intent.action) {
                // Pre-arm while the display is off so JARVIS is already on top of the keyguard
                // when the screen lights up (instead of appearing only after biometric unlock).
                Intent.ACTION_SCREEN_OFF -> showLockScreen(LockScreenActivity.Reason.ARM)
                Intent.ACTION_SCREEN_ON -> if (isLocked() && !LockScreenActivity.visible) showLockScreen(LockScreenActivity.Reason.ARM)
                Intent.ACTION_POWER_CONNECTED -> {
                    val pm = getSystemService(PowerManager::class.java)
                    if (!pm.isInteractive || isLocked()) showLockScreen(LockScreenActivity.Reason.CHARGER)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        app.handsFree.onWake = {
            // Wake word heard: light up the JARVIS lock screen if the phone is locked/asleep.
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isInteractive || isLocked()) showLockScreen(LockScreenActivity.Reason.WAKE_WORD)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            app.prefs.setHandsFree(false)
        }
        goForeground()
        // Hands-free resumes as soon as the launcher is next in the foreground if the mic type was refused.
        if (app.prefs.handsFree.value && micForeground) app.handsFree.enable() else app.handsFree.disable()
        if (!app.prefs.handsFree.value && !app.prefs.lockScreen.value) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        app.handsFree.onWake = null
        app.handsFree.disable()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun showLockScreen(reason: LockScreenActivity.Reason) {
        // Starting UI from the background needs "Display over other apps" on Android 10+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(this, LockScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(LockScreenActivity.EXTRA_REASON, reason.name)
            )
        }
    }

    /** True when the microphone foreground type was granted (it can't be when restarted from the background). */
    private var micForeground = false

    private fun goForeground() {
        val wantMic = app.prefs.handsFree.value && micGranted()
        val other = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        micForeground = wantMic && runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.isSuccess
        if (!micForeground) ServiceCompat.startForeground(this, NOTIF_ID, notification(false), other)
    }

    private fun notification(listening: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (listening) "Listening for \"Hey JARVIS\"" else "JARVIS lock screen active")
            .setContentText(if (listening) "Voice stays on this device." else "Shown each time the screen turns on.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
        if (listening) {
            val pause = PendingIntent.getService(
                this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, "Stop listening", pause)
        }
        return b.build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS background", NotificationManager.IMPORTANCE_MIN))
    }

    companion object {
        private const val CHANNEL = "jarvis_bg"
        private const val NOTIF_ID = 7
        const val ACTION_PAUSE = "com.jarvis.launcher.PAUSE_HANDSFREE"

        /** Start, update or stop the service to match the current settings. Call while the app is in the foreground. */
        fun sync(context: Context) {
            val app = context.applicationContext as JarvisApp
            val wanted = app.prefs.handsFree.value || app.prefs.lockScreen.value
            val i = Intent(context, JarvisService::class.java)
            if (wanted) runCatching { ContextCompat.startForegroundService(context, i) }
            else context.stopService(i)
        }
    }
}
