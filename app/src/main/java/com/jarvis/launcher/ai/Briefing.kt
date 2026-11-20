package com.jarvis.launcher.ai

import com.jarvis.launcher.data.Note
import com.jarvis.launcher.data.Prefs
import com.jarvis.launcher.system.SystemMonitor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Pulls "for tomorrow" / "on friday" out of a note. Pure so it is unit-tested. */
object NoteDates {
    private val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Returns the note text without the date phrase, and the start-of-day it is for (or null). */
    fun extract(text: String, now: Calendar = Calendar.getInstance()): Pair<String, Long?> {
        val t = text.trim()
        val rules = listOf<Pair<Regex, (MatchResult) -> Int?>>(
            Regex("\\b(?:for |on )?(?:the )?day after tomorrow\\b[:,]?", RegexOption.IGNORE_CASE) to { _ -> 2 },
            Regex("\\b(?:for |by )?tomorrow\\b[:,]?", RegexOption.IGNORE_CASE) to { _ -> 1 },
            Regex("\\b(?:for |by )?today\\b[:,]?", RegexOption.IGNORE_CASE) to { _ -> 0 },
            Regex("\\b(?:for |on |this |next |by )?(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b[:,]?", RegexOption.IGNORE_CASE) to { m ->
                val target = days.indexOf(m.groupValues[1].lowercase())
                val today = now.get(Calendar.DAY_OF_WEEK) - 1
                var diff = (target - today + 7) % 7
                if (diff == 0 || m.value.lowercase().startsWith("next")) diff += 7
                diff
            },
        )
        for ((re, offset) in rules) {
            val m = re.find(t) ?: continue
            val add = offset(m) ?: continue
            val cleaned = t.removeRange(m.range).replace(Regex("\\s+"), " ").trim().trim(',', ':', '-').trim()
            if (cleaned.isEmpty()) continue
            val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, add) }
            return cleaned to startOfDay(day)
        }
        return t to null
    }

    fun label(day: Long, now: Calendar = Calendar.getInstance()): String {
        val today = startOfDay(now)
        val diffDays = Math.round((day - today) / 86_400_000.0).toInt()
        return when (diffDays) {
            0 -> "today"
            1 -> "tomorrow"
            -1 -> "yesterday"
            else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(day))
        }
    }
}

/**
 * "What do you have for me today?" Notes meant for today (plus overdue ones), today's
 * scheduled tasks, location tasks still waiting, your next alarm and the local weather.
 */
class Briefing(
    private val prefs: Prefs,
    private val tasks: TaskEngine,
    private val monitor: SystemMonitor,
    private val web: WebKnowledge,
) {
    companion object {
        val trigger = Regex(
            "(what (do|have) you (got|have) for me|what do you have for me|anything for me|" +
                "(daily |morning |evening )?briefing|brief me|what's my day|what is my day|how does my day look|" +
                "what('s| is) on (my )?(agenda|schedule|plate|calendar)|what('s| is) on (for )?today|what do i have (today|planned)|" +
                "what('s| is) (my )?(plan|agenda|schedule) (for )?today|my day today|catch me up|what did i miss)"
        )
    }

    fun matches(raw: String) = trigger.containsMatchIn(raw.lowercase())

    suspend fun compose(): String {
        val title = prefs.userTitle.value
        val now = Calendar.getInstance()
        val today = NoteDates.startOfDay(now)
        val endOfToday = today + 86_400_000L
        val parts = ArrayList<String>()

        val greet = when (now.get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        parts += "$greet, $title. It's ${SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now.time)}."

        val notes = prefs.notes.value
        val dueToday = notes.filter { it.forDay != null && it.forDay <= today }
        val upcoming = notes.filter { it.forDay != null && it.forDay > today }.sortedBy { it.forDay }
        val general = notes.filter { it.forDay == null }
        if (dueToday.isNotEmpty()) {
            val overdue = dueToday.count { it.forDay!! < today }
            parts += "Notes for today: " + dueToday.joinToString("; ") { it.text } + "." +
                if (overdue > 0) " ($overdue carried over from earlier.)" else ""
        }

        val timed = tasks.tasks.value.mapNotNull { t -> (t.trigger as? Trigger.At)?.let { t to it.epochMs } }
            .filter { it.second in now.timeInMillis until endOfToday }.sortedBy { it.second }
        if (timed.isNotEmpty()) {
            val f = SimpleDateFormat("h:mm a", Locale.getDefault())
            parts += "Scheduled today: " + timed.joinToString("; ") { (t, at) -> "${f.format(Date(at))}, ${t.command.removePrefix("remind me to ")}" } + "."
        }
        val geo = tasks.tasks.value.filter { it.trigger !is Trigger.At }
        if (geo.isNotEmpty()) parts += "Waiting on location: " + geo.joinToString("; ") { it.describe() } + "."

        monitor.snapshot().nextAlarm?.let { parts += "Your next alarm is $it." }

        if (prefs.webEnabled.value && web.online) {
            val w = runCatching { web.answer(WebKnowledge.Query.Weather(null, false), title) }.getOrNull()
            if (w != null && w.permission == null) parts += "Weather: " + w.text.substringBefore(". Today").trim().removeSuffix(".") + "."
        }

        if (upcoming.isNotEmpty()) {
            parts += "Coming up: " + upcoming.take(3).joinToString("; ") { "${NoteDates.label(it.forDay!!, now)}, ${it.text}" } + "."
        }
        if (general.isNotEmpty()) {
            parts += "Your notes: " + general.takeLast(5).reversed().joinToString("; ") { it.text } + "."
        }
        if (dueToday.isEmpty() && timed.isEmpty() && geo.isEmpty() && general.isEmpty() && upcoming.isEmpty()) {
            parts += "Nothing on the books. Say \"note for tomorrow\" followed by anything, and I'll bring it up in your briefing."
        }
        val s = monitor.snapshot()
        if (s.batteryPct in 1..20 && !s.charging) parts += "Power is at ${s.batteryPct} percent; I'd find a charger."
        return parts.joinToString(" ")
    }

    /** Notes for "today" that already passed are kept; this lets the user clear finished ones. */
    fun completeMatching(words: List<String>): Note? =
        prefs.notes.value.maxByOrNull { n -> words.count { n.text.lowercase().contains(it) } }
            ?.takeIf { n -> words.any { n.text.lowercase().contains(it) } }
            ?.also { prefs.removeNote(it) }
}
