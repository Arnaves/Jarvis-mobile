package com.jarvis.launcher

import com.jarvis.launcher.ai.Briefing
import com.jarvis.launcher.ai.NoteDates
import com.jarvis.launcher.ai.TaskParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotesBriefingTest {
    /** Friday 20 Nov 2026, 09:00. */
    private fun now() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 20, 9, 0, 0); set(Calendar.MILLISECOND, 0) }
    private fun day(d: Int) = NoteDates.startOfDay(Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, d, 12, 0, 0) })

    @Test fun tomorrow() = assertEquals("call the bank" to day(21), NoteDates.extract("for tomorrow: call the bank", now()))
    @Test fun trailingToday() = assertEquals("buy milk" to day(20), NoteDates.extract("buy milk today", now()))
    @Test fun weekday() = assertEquals("dentist at 5" to day(23), NoteDates.extract("dentist at 5 on monday", now()))
    @Test fun sameWeekdayMeansNextWeek() = assertEquals("team lunch" to day(27), NoteDates.extract("team lunch friday", now()))
    @Test fun dayAfter() = assertEquals("pay rent" to day(22), NoteDates.extract("pay rent day after tomorrow", now()))
    @Test fun undated() = assertEquals("wifi password is tiger42" to null, NoteDates.extract("wifi password is tiger42", now()))
    @Test fun labels() {
        assertEquals("today", NoteDates.label(day(20), now()))
        assertEquals("tomorrow", NoteDates.label(day(21), now()))
    }

    @Test fun briefingPhrases() {
        listOf(
            "what do you have for me today", "Hey Jarvis, what do you have for me?", "morning briefing", "brief me",
            "what's on my agenda", "what is on today", "what's my day look like", "anything for me today", "catch me up",
        ).forEach { assertTrue(it, Briefing.trigger.containsMatchIn(it.lowercase())) }
    }
    @Test fun notBriefing() = assertFalse(Briefing.trigger.containsMatchIn("what is the capital of france"))

    @Test fun notesAreNotScheduledTasks() = assertNull(TaskParser.parse("remember to call dad tomorrow", now()))
    @Test fun noteForTomorrowNotScheduled() = assertNull(TaskParser.parse("note for tomorrow call the bank at 5 pm", now()))
}
