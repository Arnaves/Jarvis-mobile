package com.jarvis.launcher

import com.jarvis.launcher.ai.Contact
import com.jarvis.launcher.ai.Contacts
import com.jarvis.launcher.ai.TaskParser
import com.jarvis.launcher.ai.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TaskParserTest {
    /** Thursday 19 Nov 2026, 14:00 local. */
    private fun now() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 19, 14, 0, 0); set(Calendar.MILLISECOND, 0) }

    private fun at(day: Int, h: Int, m: Int = 0) =
        Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, day, h, m, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    @Test fun whatsappOnArrival() {
        val p = TaskParser.parse("Hey Jarvis send a WhatsApp to mummy kolkata saying good morning reached office when I reach office", now())!!
        assertEquals("send a whatsapp to mummy kolkata saying good morning reached office", p.command)
        assertEquals(Trigger.Arrive("office"), p.trigger)
    }

    @Test fun locationFiller() {
        val p = TaskParser.parse("send a whatsapp to mummy saying reached when I actually reach office according to my location", now())!!
        assertEquals(Trigger.Arrive("office"), p.trigger)
        assertEquals("send a whatsapp to mummy saying reached", p.command)
    }

    @Test fun clauseFirst() {
        val p = TaskParser.parse("when I get home text Riya saying I'm back", now())!!
        assertEquals(Trigger.Arrive("home"), p.trigger)
        assertEquals("text riya saying i'm back", p.command)
    }

    @Test fun leaving() {
        val p = TaskParser.parse("remind me to buy milk when I leave work", now())!!
        assertEquals(Trigger.Leave("work"), p.trigger)
        assertEquals("remind me to buy milk", p.command)
    }

    @Test fun relativeTime() {
        val p = TaskParser.parse("remind me in 20 minutes to call dad", now())!!
        assertEquals(Trigger.At(now().timeInMillis + 20 * 60_000L), p.trigger)
        assertEquals("remind me to call dad", p.command)
    }

    @Test fun absolutePm() {
        val p = TaskParser.parse("at 6 pm call mom", now())!!
        assertEquals(Trigger.At(at(19, 18)), p.trigger)
        assertEquals("call mom", p.command)
    }

    @Test fun bareHourMeansNextOccurrence() =
        assertEquals(Trigger.At(at(19, 18)), TaskParser.parse("call mom at 6", now())!!.trigger)

    @Test fun tomorrowMorning() {
        val p = TaskParser.parse("tomorrow morning remind me to pay rent", now())!!
        assertEquals(Trigger.At(at(20, 9)), p.trigger)
        assertEquals("remind me to pay rent", p.command)
    }

    @Test fun tomorrowAt() = assertEquals(Trigger.At(at(20, 7, 30)), TaskParser.parse("tomorrow at 7:30 am text boss saying on leave", now())!!.trigger)

    @Test fun timeInsideMessageBodyIsNotATrigger() = assertNull(TaskParser.parse("text Sam saying meet me at 5 pm", now()))

    @Test fun alarmsAreNotTasks() = assertNull(TaskParser.parse("set an alarm at 7 am", now()))
    @Test fun timersAreNotTasks() = assertNull(TaskParser.parse("set a timer for 10 minutes", now()))
    @Test fun plainCommand() = assertNull(TaskParser.parse("open camera", now()))
}

class ContactMatchTest {
    private val book = listOf(
        Contact("Mummy (Kolkata)", "+91 98300 00001"),
        Contact("Mummy Ji", "+91 98300 00002"),
        Contact("Rahul Office", "+91 98300 00003"),
        Contact("Dr. Mehta Clinic", "+91 98300 00004"),
        Contact("Papa", "+91 98300 00005"),
    )

    @Test fun exactWithPunctuation() = assertEquals("Mummy (Kolkata)", Contacts.best("mummy kolkata", book)?.name)
    @Test fun prefix() = assertEquals("Dr. Mehta Clinic", Contacts.best("dr mehta", book)?.name)
    @Test fun misheard() = assertEquals("Papa", Contacts.best("pappa", book)?.name)
    @Test fun nobody() = assertNull(Contacts.best("elon musk", book))
    @Test fun devanagari() = assertEquals("मम्मी", Contacts.best("मम्मी", listOf(Contact("मम्मी", "1"), Contact("Papa", "2")))?.name)
    @Test fun ambiguousPrefersMoreSpecific() = assertTrue(Contacts.best("mummy", book)?.name?.startsWith("Mummy") == true)
}
