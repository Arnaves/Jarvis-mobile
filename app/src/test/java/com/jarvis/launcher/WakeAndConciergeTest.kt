package com.jarvis.launcher

import androidx.test.core.app.ApplicationProvider
import com.jarvis.launcher.ai.Concierge
import com.jarvis.launcher.ai.Contacts
import com.jarvis.launcher.ai.WakeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeDecisionTest {
    private fun r(vararg w: Pair<String, Double>) =
        """{"result":[${w.joinToString(",") { """{"word":"${it.first}","conf":${it.second}}""" }}],"text":"${w.joinToString(" ") { it.first }}"}"""

    @Test fun clearHeyJarvis() = assertTrue(WakeDecision.isWake(r("hey" to 0.97, "jarvis" to 0.99)))
    @Test fun okJarvisInsideSentence() = assertTrue(WakeDecision.isWake(r("so" to 0.6, "okay" to 0.9, "jarvis" to 0.95)))
    @Test fun lowConfidenceRejected() = assertFalse(WakeDecision.isWake(r("hey" to 0.9, "jarvis" to 0.55)))
    @Test fun jarvisAloneRejected() = assertFalse(WakeDecision.isWake(r("jarvis" to 1.0)))
    @Test fun noiseRejected() = assertFalse(WakeDecision.isWake("""{"text":""}"""))
    @Test fun decoyRejected() = assertFalse(WakeDecision.isWake(r("hey" to 0.9, "travis" to 0.99)))
    @Test fun garbage() = assertFalse(WakeDecision.isWake("not json"))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConciergeTest {
    private val c: Concierge by lazy {
        val app = ApplicationProvider.getApplicationContext<JarvisApp>()
        Concierge(app, app.prefs, Contacts(app), app.apps)
    }

    @Test fun table() {
        val r = c.classify("Hey Jarvis please book a table for two at Peter Cat at 8 pm") as Concierge.Request.Table
        assertEquals("peter cat", r.place); assertEquals(2, r.party); assertEquals("8 pm", r.time)
    }
    @Test fun tableTonight() {
        val r = c.classify("reserve a table for 4 at the olive bistro tonight") as Concierge.Request.Table
        assertEquals("the olive bistro", r.place); assertEquals(4, r.party); assertEquals("tonight", r.time)
    }
    @Test fun appointment() {
        val r = c.classify("call dr mehta clinic and take an appointment for tomorrow morning") as Concierge.Request.Appointment
        assertEquals("dr mehta clinic", r.place); assertEquals("tomorrow morning", r.purpose)
    }
    @Test fun appointmentWith() = assertEquals("the dentist", c.classify("book an appointment with the dentist")?.place)
    @Test fun plainCall() = assertNull(c.classify("call mom"))
}
