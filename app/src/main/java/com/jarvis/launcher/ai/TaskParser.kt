package com.jarvis.launcher.ai

import java.util.Calendar

/** What should start a deferred task. */
sealed interface Trigger {
    data class Arrive(val place: String) : Trigger
    data class Leave(val place: String) : Trigger
    data class At(val epochMs: Long) : Trigger
}

data class ParsedTask(val command: String, val trigger: Trigger)

/**
 * Splits "send a whatsapp to mummy saying reached office when I reach office"
 * into a command and a trigger. Pure Kotlin so it is unit-tested on the JVM.
 */
object TaskParser {
    private const val VERBS = "send|text|message|whatsapp|call|remind|play|turn|open|set|tell|ring|dial|navigate|switch|start"
    private val bodyMarker = Regex("\\b(saying|that says|which says|say|telling (?:her|him|them))\\b")

    private val geo = Regex(
        "(?:^|,?\\s+)(?:and\\s+)?(?:when|once|as soon as|whenever|the moment|after)\\s+i\\s+" +
            "(reach|get to|get back to|arrive at|arrive|am at|enter|get|come to|leave|exit|left|depart from|depart)\\s+" +
            "(?:the |my |at |to )?([a-z0-9' ]+?)" +
            "(?=\\s*(?:,|\\bthen\\b|\\b(?:$VERBS)\\b|\\baccording\\b|\\bbased\\b|\\bplease\\b|$))"
    )
    private val filler = Regex("\\s*,?\\s*(?:according to|based on|using|as per) (?:my |the )?(?:gps|location|current location)\\s*")

    fun parse(raw: String, now: Calendar = Calendar.getInstance()): ParsedTask? {
        var q = raw.lowercase().replace(Regex("^(hey |ok |okay |hi )?jarvis[,.!]?\\s*"), "")
            .replace(Regex("\\b(please|kindly|actually)\\b"), " ")
            .replace(filler, " ")
            .replace(Regex("[?!]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.')

        // --- location trigger (allowed anywhere: "when I reach X" is an unambiguous signal)
        geo.find(q)?.let { m ->
            val place = m.groupValues[2].trim().removeSuffix(" area").trim()
            if (place.isNotEmpty()) {
                val cmd = tidy(q.removeRange(m.range))
                if (cmd.isNotEmpty()) {
                    val leaving = m.groupValues[1].let { it.startsWith("leav") || it.startsWith("exit") || it.startsWith("left") || it.startsWith("depart") }
                    return ParsedTask(cmd, if (leaving) Trigger.Leave(place) else Trigger.Arrive(place))
                }
            }
        }

        // --- time trigger (only for actions; never for alarms/timers which are immediate)
        if (Regex("^(set )?(an |a )?(alarm|timer)\\b").containsMatchIn(q) || q.contains("timer")) return null
        if (!Regex("\\b(?:$VERBS)\\b").containsMatchIn(q)) return null
        // Don't read times inside a message body ("saying meet me at 5").
        val marker = bodyMarker.find(q)
        val isReminder = q.contains("remind me")
        val searchEnd = if (marker != null && !isReminder) marker.range.first else q.length
        val head = q.substring(0, searchEnd)

        val rel = Regex("\\bin (\\d+|an?|one|two|three|four|five|ten|fifteen|twenty|thirty|forty five|half an?) ?(minutes?|mins?|hours?|hrs?)\\b").find(head)
            ?: Regex("\\bin half an hour\\b").find(head)
        if (rel != null) {
            val minutes = if (rel.value.contains("half")) 30 else {
                val n = NumberWords.parse(rel.groupValues[1]) ?: 1
                if (rel.groupValues[2].startsWith("h")) n * 60 else n
            }
            val cmd = tidy(q.removeRange(rel.range))
            return ParsedTask(cmd, Trigger.At(now.timeInMillis + minutes * 60_000L))
        }

        val tomorrow = Regex("\\btomorrow\\b").containsMatchIn(head)
        val abs = Regex("\\b(?:at|by) (\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?=\\s|$)").find(head)
        val partOfDay = Regex("\\b(tomorrow morning|this evening|tonight|tomorrow evening|tomorrow night|in the morning|this afternoon)\\b").find(head)
        if (abs == null && partOfDay == null && !tomorrow) return null

        val cal = (now.clone() as Calendar).apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val removeRanges = mergeRanges(listOfNotNull(abs?.range, partOfDay?.range, Regex("\\btomorrow\\b").find(q)?.range))
        if (abs != null) {
            var h = abs.groupValues[1].toInt()
            val min = abs.groupValues[2].toIntOrNull() ?: 0
            val ap = abs.groupValues[3].replace(".", "")
            if (h > 23 || min > 59) return null
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            val pmHint = partOfDay?.value?.let { it.contains("evening") || it.contains("night") || it.contains("afternoon") } == true
            if (ap.isEmpty() && pmHint && h < 12) h += 12
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min)
            if (tomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
            else if (cal.timeInMillis <= now.timeInMillis) {
                // "at 6" said at 14:00 means 18:00; otherwise roll to tomorrow.
                if (ap.isEmpty() && h < 12 && (cal.timeInMillis + 12 * 3_600_000L) > now.timeInMillis) cal.add(Calendar.HOUR_OF_DAY, 12)
                else cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            val (h, addDay) = when (partOfDay?.value) {
                "tomorrow morning", "in the morning" -> 9 to (partOfDay.value.startsWith("tomorrow") || now.get(Calendar.HOUR_OF_DAY) >= 9)
                "this afternoon" -> 14 to false
                "this evening" -> 18 to false
                "tonight" -> 20 to false
                "tomorrow evening" -> 18 to true
                "tomorrow night" -> 20 to true
                else -> 9 to true // bare "tomorrow"
            }
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, 0)
            if (addDay) cal.add(Calendar.DAY_OF_YEAR, 1)
            if (cal.timeInMillis <= now.timeInMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        var cmd = q
        for (r in removeRanges.sortedByDescending { it.first }) cmd = cmd.removeRange(r)
        cmd = tidy(cmd)
        if (cmd.isEmpty()) return null
        return ParsedTask(cmd, Trigger.At(cal.timeInMillis))
    }

    private fun mergeRanges(rs: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in rs.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").trim()
        .removePrefix(",").removePrefix("then ").removePrefix("and ").trim().removeSuffix(",").removeSuffix(" and").removeSuffix(" then").trim()
}
