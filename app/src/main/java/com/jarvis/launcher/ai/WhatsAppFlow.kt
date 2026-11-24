package com.jarvis.launcher.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.data.Prefs
import com.jarvis.launcher.service.JarvisAccessibility

/**
 * WhatsApp: draft → check → send.
 *  - Default: prepare the message in the right chat and wait for "send it".
 *  - Direct message (setting): send straight away (verified recipient + text).
 *  - "draft a WhatsApp…" never sends, even with Direct message on.
 *  - Several recipients ("Rahul and Priya") are processed one after another.
 *  - Native-script text is written in Roman letters if enabled.
 */
class WhatsAppFlow(
    private val context: Context,
    private val prefs: Prefs,
    private val contacts: Contacts,
    private val apps: AppRepository,
) {
    data class Draft(val recipients: List<Contact>, val body: String, val pkg: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile var pending: Draft? = null; private set
    private val queue = ArrayDeque<Contact>()
    /** Set for scheduled tasks: there's nobody to say "send it", so send directly. */
    @Volatile var forceDirect = false

    /** Lets the assistant speak progress ("Sent to Priya."). */
    var announce: ((String) -> Unit)? = null

    private val title get() = prefs.userTitle.value
    private val wa = "(?:whatsapp|whats app|what's app|watsapp|whatsap)"
    private val marker = "(?:saying|that says|which says|that|say|telling (?:her|him|them)|with the message|with)"

    fun handle(q: String, raw: String): Reply? {
        if (pending != null) {
            if (Regex("^(yes,? )?(send|send it|send that|send the message|go ahead|confirm|yes send( it)?|do it)$").matches(q)) return sendPending()
            if (Regex("^(no|cancel|don't send|do not send|stop|discard( it)?|never mind)$").matches(q)) {
                pending = null; return Reply("Cancelled. Nothing was sent.")
            }
        }
        val draftOnly = Regex("^(?:draft|prepare|write) ").containsMatchIn(q)
        val m = Regex("^(?:send |write |drop |draft |prepare )?(?:a |an )?$wa(?: message| msg| text)?(?: to)? (.+?),? $marker\\s+(.+)$").find(q)
            ?: Regex("^(?:send |write |draft )?(?:a )?(?:message|text|msg) (?:to )?(.+?) on $wa,? $marker\\s+(.+)$").find(q)
            ?: Regex("^$wa (.+?),? $marker\\s+(.+)$").find(q)
            ?: return null
        val who = m.groupValues[1].removePrefix("to ").trim()
        var body = originalBody(raw) ?: m.groupValues[2]
        body = body.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        if (prefs.romanLetters.value) body = Roman.toRoman(body)

        val recipients = resolveRecipients(who) ?: return problem(who)
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
            ?: return Reply("WhatsApp isn't installed, $title.")
        val draft = Draft(recipients, body, pkg)
        val names = recipients.joinToString(" and ") { it.name }
        val canSend = JarvisAccessibility.connected

        if (!draftOnly && (prefs.directMessage.value || forceDirect) && canSend) {
            pending = draft
            return sendPending()
        }
        pending = draft
        return Reply(
            "Draft for $names: \"$body\". " + when {
                canSend -> "Say \"send it\" to send, or \"cancel\"."
                else -> "Tap send in WhatsApp. (Enable JARVIS screen control to send by voice.)"
            },
            leavesLauncher = recipients.size == 1,
            action = { if (recipients.size == 1) open(pkg, recipients[0], body) },
        )
    }

    private fun sendPending(): Reply {
        val d = pending ?: return Reply("There's nothing waiting to be sent.")
        if (!JarvisAccessibility.connected) {
            return Reply("I need screen control to press send, $title. Enable JARVIS in Accessibility settings.")
        }
        pending = null
        queue.clear(); queue.addAll(d.recipients)
        val names = d.recipients.joinToString(" and ") { it.name }
        return Reply("Sending to $names.", leavesLauncher = true, action = { next(d) })
    }

    /** Opens each chat with the text and lets the accessibility service press Send after checking the chat. */
    private fun next(d: Draft) {
        val c = queue.removeFirstOrNull() ?: return
        val last = queue.isEmpty()
        JarvisAccessibility.onSent = {
            main.post {
                announce?.invoke("Sent to ${c.name}.")
                if (!last) main.postDelayed({ next(d) }, 1200) else JarvisAccessibility.onSent = null
            }
        }
        JarvisAccessibility.armSend(recipient = c.name, windowMs = 15_000, goHome = last)
        open(d.pkg, c, d.body)
    }

    private fun open(pkg: String, c: Contact, body: String) {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${contacts.international(c.number)}&text=" + Uri.encode(body))
        apps.startSafely(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    /** "rahul and priya", "rahul, priya and amit" — but keep names that contain "and" if they match a contact. */
    private fun resolveRecipients(who: String): List<Contact>? {
        contacts.find(who)?.let { whole ->
            val parts = splitNames(who)
            if (parts.size == 1 || Contacts.norm(whole.name).contains(Contacts.norm(who))) return listOf(whole)
        }
        val parts = splitNames(who)
        if (parts.size < 2) return contacts.find(who)?.let { listOf(it) }
        val found = parts.map { contacts.find(it) ?: return null }
        return found.distinctBy { it.number }
    }

    private fun problem(who: String): Reply =
        if (!contacts.granted) Reply("I need access to your contacts to do that, $title.", permission = android.Manifest.permission.READ_CONTACTS)
        else Reply("I couldn't find $who in your contacts.")

    /** The message exactly as spoken/typed (keeps capitals and native script). */
    private fun originalBody(raw: String): String? =
        Regex("\\b$marker\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(raw)?.groupValues?.get(1)

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    companion object {
        fun splitNames(s: String): List<String> =
            s.split(Regex("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
