package com.jarvis.launcher.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jarvis.launcher.service.JarvisAccessibility

/**
 * SONIC-style screen control by voice: read, tap, type, paste, scroll, swipe,
 * back/home/recents, notifications, lock, screenshot. Needs JARVIS screen control
 * (Accessibility) to be enabled and connected.
 */
class ScreenSkills(private val context: Context, private val title: () -> String) {
    private val main = Handler(Looper.getMainLooper())

    /** Commands are delayed slightly so the launcher can step aside and the target app is in front. */
    private fun later(ms: Long = 450, block: (JarvisAccessibility) -> Unit) = main.postDelayed({
        JarvisAccessibility.instance?.let(block)
    }, ms)

    private fun needsService(): Reply = Reply(
        "I need screen control for that, ${title()}. Enable \"JARVIS\" in Accessibility settings, then ask again.",
        action = { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        leavesLauncher = true,
    )

    fun handle(q: String): Reply? {
        // Clipboard works without screen control.
        Regex("^copy (?:the text |this text )?(.+?)(?: to (?:the |my )?clipboard)?$").find(q)?.let { m ->
            if (m.groupValues[1] in setOf("that", "this", "it")) return null
            val text = m.groupValues[1]
            return Reply("Copied to clipboard: \"$text\".", action = { JarvisAccessibility.copyToClipboard(context, text) })
        }

        val cmd = classify(q) ?: return null
        if (!JarvisAccessibility.connected) return needsService()
        val svc = JarvisAccessibility.instance!!
        val onJarvis = svc.foregroundPackage == context.packageName
        if (onJarvis && (cmd is Cmd.Read || cmd is Cmd.Tap || cmd is Cmd.Type || cmd == Cmd.Paste || cmd == Cmd.Enter)) {
            return Reply("I act on whatever app is open, ${title()}. Open the app first, then say it with the wake word.")
        }
        return when (cmd) {
            is Cmd.Global -> Reply(cmd.say, leavesLauncher = cmd.leaves, action = { later { it.global(cmd.action) } })
            is Cmd.Read -> {
                val text = svc.readScreen()
                if (text.isBlank()) Reply("I can't read anything on this screen.")
                else Reply("On screen: $text")
            }
            is Cmd.Tap -> Reply("Tapping ${cmd.target}.", leavesLauncher = true, action = {
                later { s -> if (s.tap(cmd.target) == null) main.post { onFailure?.invoke("I couldn't find \"${cmd.target}\" on screen.") } }
            })
            is Cmd.Type -> Reply("Typing.", action = {
                later(250) { s -> if (!s.type(cmd.text)) main.post { onFailure?.invoke("There's no text field to type into.") } }
            })
            Cmd.Paste -> Reply("Pasted.", action = { later(250) { it.paste() } })
            Cmd.Clear -> Reply("Cleared.", action = { later(250) { it.clearField() } })
            Cmd.Enter -> Reply("Done.", action = { later(250) { it.pressEnter() } })
            is Cmd.Scroll -> Reply(if (cmd.forward) "Scrolling down." else "Scrolling up.", action = { later { it.scroll(cmd.forward) } })
            is Cmd.Swipe -> Reply("Swiping ${cmd.dir}.", action = { later { it.swipe(cmd.dir) } })
        }
    }

    /** Reports a deferred failure (set by the Assistant so it can speak it). */
    var onFailure: ((String) -> Unit)? = null

    sealed interface Cmd {
        data class Global(val action: Int, val say: String, val leaves: Boolean = false) : Cmd
        data object Read : Cmd
        data class Tap(val target: String) : Cmd
        data class Type(val text: String) : Cmd
        data object Paste : Cmd
        data object Clear : Cmd
        data object Enter : Cmd
        data class Scroll(val forward: Boolean) : Cmd
        data class Swipe(val dir: String) : Cmd
    }

    companion object {
        /** Pure classifier, unit-tested. */
        fun classify(q: String): Cmd? {
            when {
                Regex("^(go |navigate )?back$|^press back$").matches(q) -> return Cmd.Global(AccessibilityService.GLOBAL_ACTION_BACK, "Going back.")
                Regex("^(go (to )?home|go to (the )?home ?screen|home screen)$").matches(q) -> return Cmd.Global(AccessibilityService.GLOBAL_ACTION_HOME, "Home.")
                Regex("^(show |open )?(the )?recent(s| apps)$").matches(q) -> return Cmd.Global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps.")
                Regex("^(open|show|pull down|check)( my| the)? notifications?( panel| shade)?$").matches(q) ->
                    return Cmd.Global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications.", leaves = true)
                Regex("^(open |show )?(the )?quick settings$").matches(q) ->
                    return Cmd.Global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings.", leaves = true)
                Regex("^(lock (the |my )?(phone|screen|device)|screen off|turn off (the )?screen)$").matches(q) && Build.VERSION.SDK_INT >= 28 ->
                    return Cmd.Global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Locking.", leaves = true)
                Regex("^(take |capture )?(a )?screenshot$|^screen ?shot$").matches(q) && Build.VERSION.SDK_INT >= 28 ->
                    return Cmd.Global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken.", leaves = true)
                Regex("^(read|read out|read me) (the |my |this )?(screen|page)$").matches(q) -> return Cmd.Read
                Regex("^(paste|paste it|paste that)$").matches(q) -> return Cmd.Paste
                Regex("^(clear|erase|delete) (the )?(text|field|text field)$").matches(q) -> return Cmd.Clear
                Regex("^(press |hit )?(enter|submit|search it|go)$").matches(q) -> return Cmd.Enter
                Regex("^scroll (down|up)$").matches(q) -> return Cmd.Scroll(q.endsWith("down"))
                Regex("^(scroll|page) (down|up)( a bit)?$").matches(q) -> return Cmd.Scroll(q.contains("down"))
                Regex("^swipe (left|right|up|down)$").matches(q) -> return Cmd.Swipe(q.substringAfter("swipe ").trim())
            }
            Regex("^(?:tap|click|press|select|hit|tap on|click on) (?:on )?(?:the )?(.+?)(?: button| tab| option| icon)?$").find(q)?.let {
                val t = it.groupValues[1].trim()
                if (t !in setOf("back", "home", "enter")) return Cmd.Tap(t)
            }
            Regex("^(?:type|dictate|enter text|write in the box)[:,]? (.+)$").find(q)?.let { return Cmd.Type(it.groupValues[1]) }
            return null
        }
    }
}
