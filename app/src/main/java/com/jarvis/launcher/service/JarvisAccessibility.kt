package com.jarvis.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * JARVIS screen control. Acts only when a voice/text command asks it to:
 * read the screen, tap, type, scroll, swipe, global navigation, lock, screenshots,
 * and pressing Send in WhatsApp for a message JARVIS just prepared.
 */
class JarvisAccessibility : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val root = rootInActiveWindow ?: return
        // Only send when the open chat is the one JARVIS prepared.
        expectedChat?.let { name -> if (!chatHeaderMatches(root, name)) return }
        val send = findSend(root, pkg) ?: return
        if (send.isEnabled && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0
            sentCount++
            onSent?.invoke()
            if (returnHomeAfterSend) main.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 600)
        }
    }

    private fun chatHeaderMatches(root: AccessibilityNodeInfo, name: String): Boolean {
        val want = name.lowercase().split(' ').filter { it.length > 1 }
        val header = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/conversation_contact_name").firstOrNull()
            ?: return true // layout changed: fall back to the deep link having opened the right chat
        val text = header.text?.toString()?.lowercase().orEmpty()
        return want.any { text.contains(it) }
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()?.let { return it }
        return walk(root).firstOrNull { n ->
            val d = n.contentDescription?.toString()?.lowercase().orEmpty()
            n.isClickable && d in setOf("send", "enviar", "senden", "envoyer", "भेजें", "invia")
        }
    }

    // ------------------------------------------------------------------ screen control

    private fun walk(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root == null) return@sequence
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var n = 0
        while (q.isNotEmpty() && n++ < 3000) {
            val x = q.removeFirst()
            yield(x)
            for (i in 0 until x.childCount) x.getChild(i)?.let { q.add(it) }
        }
    }

    private fun label(n: AccessibilityNodeInfo) =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: n.hintText?.toString())
            ?.replace(Regex("\\s+"), " ")?.trim()

    /** Visible text on screen, top to bottom, de-duplicated. */
    fun readScreen(max: Int = 1800): String {
        val root = rootInActiveWindow ?: return ""
        val items = walk(root).filter { it.isVisibleToUser }.mapNotNull { n ->
            val l = label(n) ?: return@mapNotNull null
            val r = Rect().also { n.getBoundsInScreen(it) }
            Triple(r.top, r.left, l)
        }.toList().sortedWith(compareBy({ it.first / 24 }, { it.second })).map { it.third }.distinct()
        return items.joinToString(". ").take(max)
    }

    val foregroundPackage: String? get() = rootInActiveWindow?.packageName?.toString()

    /** Taps the element best matching [target]. Returns the label tapped, or null. */
    fun tap(target: String): String? {
        val root = rootInActiveWindow ?: return null
        val t = target.lowercase().trim()
        val nodes = walk(root).filter { it.isVisibleToUser }.mapNotNull { n -> label(n)?.let { n to it } }.toList()
        val hit = nodes.firstOrNull { it.second.lowercase() == t }
            ?: nodes.firstOrNull { it.second.lowercase().startsWith(t) }
            ?: nodes.firstOrNull { it.second.lowercase().contains(t) }
            ?: return null
        var c: AccessibilityNodeInfo? = hit.first
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return hit.second
        val r = Rect().also { hit.first.getBoundsInScreen(it) }
        gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
        return hit.second
    }

    private fun inputField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: walk(root).firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    /** Types [text] into the focused (or first visible) text field. */
    fun type(text: String, replace: Boolean = false): Boolean {
        val f = inputField() ?: return false
        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val existing = if (replace || f.isShowingHintText) "" else f.text?.toString().orEmpty()
        val sep = if (existing.isNotEmpty() && !existing.endsWith(" ")) " " else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + sep + text) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun paste(): Boolean = inputField()?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true

    fun clearField(): Boolean = type("", replace = true)

    fun pressEnter(): Boolean {
        val f = inputField() ?: return false
        return Build.VERSION.SDK_INT >= 30 &&
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val s = walk(root).filter { it.isScrollable && it.isVisibleToUser }
            .maxByOrNull { Rect().also { r -> it.getBoundsInScreen(r) }.let { r -> r.width() * r.height() } }
        if (s != null && s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        return swipe(if (forward) "up" else "down")
    }

    /** Swipe in a screen direction ("up" moves content up, like scrolling down). */
    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (dir) {
            "up" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.25f, 300)
            "down" -> gesture(w / 2, h * 0.25f, w / 2, h * 0.75f, 300)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 250)
            "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 250)
            else -> false
        }
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Screenshot of the current screen (Android 11+), or null. */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { cont.resume(null) }
            })
        }
    }

    /** Finds a clickable node whose description/text matches any of [labels] (YouTube like/subscribe/skip…). */
    fun tapAny(labels: List<String>): String? {
        val root = rootInActiveWindow ?: return null
        val nodes = walk(root).filter { it.isVisibleToUser }.mapNotNull { n -> label(n)?.let { n to it.lowercase() } }.toList()
        for (l in labels) {
            val hit = nodes.firstOrNull { it.second.startsWith(l) } ?: continue
            var c: AccessibilityNodeInfo? = hit.first
            while (c != null && !c.isClickable) c = c.parent
            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return hit.second
        }
        return null
    }

    companion object {
        @Volatile var instance: JarvisAccessibility? = null; private set
        @Volatile private var armedUntil = 0L
        @Volatile private var expectedChat: String? = null
        @Volatile private var returnHomeAfterSend = true
        @Volatile var sentCount = 0; private set
        @Volatile var onSent: (() -> Unit)? = null

        /** Arms one WhatsApp send for the chat with [recipient], within [windowMs]. */
        fun armSend(recipient: String? = null, windowMs: Long = 12_000, goHome: Boolean = true) {
            expectedChat = recipient
            returnHomeAfterSend = goHome
            armedUntil = System.currentTimeMillis() + windowMs
        }

        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(context.packageName + "/") }
        }

        /** Enabled in Settings *and* actually bound right now. */
        val connected: Boolean get() = instance != null

        fun copyToClipboard(context: Context, text: String) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("JARVIS", text))
        }
    }
}
