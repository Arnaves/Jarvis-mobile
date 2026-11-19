package com.jarvis.launcher.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses WhatsApp's Send button for a message JARVIS just prepared, then returns home.
 * It does nothing unless [armSend] was called in the last few seconds.
 */
class JarvisAccessibility : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (System.currentTimeMillis() > armedUntil) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val root = rootInActiveWindow ?: return
        val send = findSend(root, pkg) ?: return
        if (send.isEnabled && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            armedUntil = 0
            sentCount++
            // Give WhatsApp a moment to dispatch, then go back to the launcher.
            android.os.Handler(mainLooper).postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 600)
        }
    }

    private fun findSend(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()?.let { return it }
        // Fallback: any clickable node described as "Send" (localised builds vary).
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
            if (n.isClickable && (desc == "send" || desc == "enviar" || desc == "senden" || desc == "envoyer" || desc == "भेजें")) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile private var armedUntil = 0L
        @Volatile var sentCount = 0; private set

        fun armSend(windowMs: Long = 12_000) { armedUntil = System.currentTimeMillis() + windowMs }

        fun enabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
