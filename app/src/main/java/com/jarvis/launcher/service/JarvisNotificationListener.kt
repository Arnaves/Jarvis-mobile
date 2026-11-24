package com.jarvis.launcher.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * Notification access, used only to see and control active media sessions
 * ("what's playing", pause, next) across Spotify, YouTube Music, etc.
 */
class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, JarvisNotificationListener::class.java)

        fun granted(context: Context): Boolean =
            android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(context.packageName) == true

        /** The most relevant media session: playing first, else the most recent. */
        fun controller(context: Context): MediaController? {
            if (!granted(context)) return null
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return null
            val sessions = runCatching { msm.getActiveSessions(component(context)) }.getOrNull().orEmpty()
            return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        }

        fun nowPlaying(context: Context): String? {
            val c = controller(context) ?: return null
            val md = c.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val app = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
            }.getOrDefault(c.packageName)
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            return (if (playing) "Playing " else "Paused: ") + "\"$title\"" + (artist?.let { " by $it" } ?: "") + " on $app."
        }
    }
}
