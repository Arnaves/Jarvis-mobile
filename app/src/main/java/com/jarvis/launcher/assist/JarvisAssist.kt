package com.jarvis.launcher.assist

import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarvis.launcher.JarvisApp
import com.jarvis.launcher.MainActivity
import com.jarvis.launcher.service.JarvisService

/** Lets JARVIS be chosen as the phone's default digital assistant (hold power / swipe from a corner). */
class JarvisVoiceService : VoiceInteractionService()

class JarvisSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_ASSIST))
            hide()
        }
    }
}

/** Required by the assistant API; JARVIS uses the phone's own recogniser and its offline wake word. */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Quick Settings tile: switch hands-free listening on/off from anywhere. */
class JarvisTile : TileService() {
    private val app get() = application as JarvisApp

    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = !app.prefs.handsFree.value
        app.prefs.setHandsFree(on)
        runCatching { JarvisService.sync(this) }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = app.prefs.handsFree.value
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "JARVIS"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Listening" else "Off"
        t.updateTile()
    }
}
