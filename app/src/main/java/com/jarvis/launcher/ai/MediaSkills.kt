package com.jarvis.launcher.ai

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.service.JarvisAccessibility
import com.jarvis.launcher.service.JarvisNotificationListener

/**
 * Music & video (Spotify / YouTube search-and-play, media sessions, "what's playing"),
 * YouTube screen actions (like, subscribe, skip ad), brightness, gallery and a
 * countdown photo — the SONIC feature set.
 */
class MediaSkills(private val context: Context, private val apps: AppRepository, private val title: () -> String) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    var speak: ((String) -> Unit)? = null

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun handle(q: String): Reply? = playOn(q) ?: transport(q) ?: nowPlaying(q) ?: youtubeAction(q) ?: brightness(q) ?: gallery(q) ?: photo(q)

    // ---------------------------------------------------------------- play

    private fun playOn(q: String): Reply? {
        val m = Regex("^play (.+?)(?: on (spotify|youtube music|youtube|yt music|yt))?$").find(q) ?: return null
        val what = m.groupValues[1].trim()
        if (Regex("^(the )?(music|song|songs|something|playback|audio|it|a song)$").matches(what) && m.groupValues[2].isEmpty()) return null
        val app = m.groupValues[2]
        return when {
            app == "spotify" || (app.isEmpty() && installed(SPOTIFY)) -> spotify(what)
            app.contains("music") && installed(YT_MUSIC) -> playFromSearch(YT_MUSIC, what, "YouTube Music")
            else -> youtube(what)
        }
    }

    private fun spotify(what: String): Reply {
        if (!installed(SPOTIFY)) return Reply("Spotify isn't installed, ${title()}. Try \"play $what on YouTube\".")
        return Reply("Playing $what on Spotify.", leavesLauncher = true, action = {
            val ok = apps.startSafely(
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY)
                    .putExtra(SearchManager.QUERY, what).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            )
            if (!ok) apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(what))))
        })
    }

    private fun playFromSearch(pkg: String, what: String, name: String) = Reply("Playing $what on $name.", leavesLauncher = true, action = {
        apps.startSafely(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, what))
    })

    private fun youtube(what: String) = Reply("Searching YouTube for $what.", leavesLauncher = true, action = {
        val ok = apps.startSafely(Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", what))
        if (!ok) apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(what))))
        // With screen control, open the first result so it actually plays.
        if (JarvisAccessibility.connected) main.postDelayed({
            JarvisAccessibility.instance?.takeIf { it.foregroundPackage == YOUTUBE }?.tapAny(listOf(what.lowercase().take(18)))
        }, 3500)
    })

    // ---------------------------------------------------------------- transport + now playing

    private fun transport(q: String): Reply? {
        val code = when {
            Regex("^(pause|stop)( the)?( music| song| playback| audio| video)?$").matches(q) -> KeyEvent.KEYCODE_MEDIA_PAUSE
            Regex("^(play|resume|continue)( the)?( music| song| playback| audio| video| something)?$").matches(q) -> KeyEvent.KEYCODE_MEDIA_PLAY
            Regex("^(next|skip)( track| song| video)?$").matches(q) || q == "skip this" || q == "skip this song" -> KeyEvent.KEYCODE_MEDIA_NEXT
            Regex("^(previous|last)( track| song| video)?$|^go back a (track|song)$").matches(q) -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> null
        } ?: return null
        val msg = when (code) {
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused."
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Resuming playback."
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Next track."
            else -> "Previous track."
        }
        return Reply(msg, action = {
            val tc = JarvisNotificationListener.controller(context)?.transportControls
            when {
                tc != null && code == KeyEvent.KEYCODE_MEDIA_PAUSE -> tc.pause()
                tc != null && code == KeyEvent.KEYCODE_MEDIA_PLAY -> tc.play()
                tc != null && code == KeyEvent.KEYCODE_MEDIA_NEXT -> tc.skipToNext()
                tc != null && code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tc.skipToPrevious()
                else -> {
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
        })
    }

    private fun nowPlaying(q: String): Reply? {
        if (!Regex("(what('s| is) (playing|this song)|which song is (this|playing)|name (of )?this song|what am i listening to)").containsMatchIn(q)) return null
        if (!JarvisNotificationListener.granted(context)) return Reply(
            "I need notification access to see what's playing, ${title()}.",
            leavesLauncher = true,
            action = { apps.startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        )
        return Reply(JarvisNotificationListener.nowPlaying(context) ?: "Nothing is playing right now.")
    }

    // ---------------------------------------------------------------- YouTube screen actions

    private fun youtubeAction(q: String): Reply? {
        val labels = when {
            Regex("^(like|thumbs up)( this| the)?( video)?$").matches(q) -> listOf("like this video", "like")
            Regex("^(dislike|thumbs down)( this| the)?( video)?$").matches(q) -> listOf("dislike this video", "dislike")
            Regex("^subscribe( to (this|the) channel| to them)?$").matches(q) -> listOf("subscribe to", "subscribe")
            Regex("^(skip|close|dismiss)( the)? ads?$|^skip ad$").matches(q) -> listOf("skip ad", "skip ads", "skip", "close ad")
            else -> null
        } ?: return null
        if (!JarvisAccessibility.connected) return Reply("I need screen control for that, ${title()}. Enable JARVIS in Accessibility settings.")
        return Reply("On it.", action = {
            main.postDelayed({
                val hit = JarvisAccessibility.instance?.tapAny(labels)
                if (hit == null) speak?.invoke("I can't see that button on screen right now.")
            }, 400)
        })
    }

    // ---------------------------------------------------------------- brightness

    private fun brightness(q: String): Reply? {
        if (!q.contains("brightness") && !Regex("^(brighter|dimmer|dim the screen|make (the )?screen (brighter|dimmer))$").matches(q)) return null
        if (!Settings.System.canWrite(context)) return Reply(
            "Allow JARVIS to modify system settings first, ${title()}, then ask again.",
            leavesLauncher = true,
            action = { apps.startSafely(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) },
        )
        val cr = context.contentResolver
        val cur = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        val target = when {
            Regex("(\\d{1,3})\\s*(%|percent)?").find(q) != null ->
                (Regex("(\\d{1,3})").find(q)!!.value.toInt().coerceIn(1, 100) * 255 / 100)
            Regex("\\b(max|maximum|full|highest)\\b").containsMatchIn(q) -> 255
            Regex("\\b(min|minimum|lowest)\\b").containsMatchIn(q) -> 5
            Regex("\\b(auto|automatic|adaptive)\\b").containsMatchIn(q) -> -1
            Regex("\\b(up|increase|raise|brighter|higher)\\b").containsMatchIn(q) -> (cur + 50).coerceAtMost(255)
            Regex("\\b(down|decrease|lower|dim|dimmer|reduce)\\b").containsMatchIn(q) -> (cur - 50).coerceAtLeast(5)
            else -> return Reply("Brightness is at ${cur * 100 / 255} percent.")
        }
        return if (target < 0) Reply("Adaptive brightness on.", action = {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        }) else Reply("Brightness ${target * 100 / 255} percent.", action = {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        })
    }

    // ---------------------------------------------------------------- camera & gallery

    private fun gallery(q: String): Reply? {
        if (!Regex("^(open|show)( me)? (the |my )?(gallery|photos|pictures|camera roll)$").matches(q)) return null
        return Reply("Opening your gallery.", leavesLauncher = true, action = {
            val ok = apps.startSafely(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            if (!ok) apps.startSafely(Intent(Intent.ACTION_VIEW).setType("image/*"))
        })
    }

    private fun photo(q: String): Reply? {
        if (Regex("^(record|start recording|shoot|take) (a )?video$").matches(q)) {
            return Reply("Video camera ready.", leavesLauncher = true, action = { apps.startSafely(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)) })
        }
        val m = Regex("^(?:take|click|snap) (?:a )?(?:photo|picture|selfie|pic)(?: in (\\d+|three|five|ten) seconds?)?$").find(q) ?: return null
        val secs = m.groupValues[1].let { NumberWords.parse(it) ?: 3 }.coerceIn(1, 10)
        val auto = JarvisAccessibility.connected
        return Reply(
            if (auto) "Smile. Taking the photo in $secs." else "Camera ready. Tap the shutter.",
            leavesLauncher = true,
            action = {
                apps.startSafely(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                if (auto) {
                    for (i in secs downTo 1) main.postDelayed({ speak?.invoke("$i") }, 1500L + (secs - i) * 1000L)
                    main.postDelayed({
                        JarvisAccessibility.instance?.tapAny(listOf("shutter", "take photo", "take picture", "capture", "camera button", "photo"))
                    }, 1500L + secs * 1000L)
                }
            },
        )
    }

    companion object {
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
    }
}
