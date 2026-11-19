package com.jarvis.launcher

import androidx.test.core.app.ApplicationProvider
import com.jarvis.launcher.ai.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkillsTest {
    private lateinit var skills: Skills

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<JarvisApp>()
        skills = Skills(app, app.apps, app.prefs, app.monitor)
    }

    private fun say(s: String) = skills.handle(s)?.text

    @Test fun timerMinutes() = assertEquals("Timer set for 5 minutes.", say("Jarvis, set a timer for five minutes"))
    @Test fun timerCombined() = assertEquals("Timer set for 1 hour 30 minutes.", say("timer for 1 hour 30 minutes"))
    @Test fun alarm() = assertEquals("Alarm set for 19:30.", say("set an alarm for 7:30 p.m."))
    @Test fun maths() = assertEquals("That comes to 43.2.", say("what's 18% of 240?"))
    @Test fun time() = assertTrue(say("what time is it")!!.startsWith("It's "))
    @Test fun status() = assertTrue(say("run a diagnostic")!!.startsWith("All systems nominal"))
    @Test fun remember() {
        assertEquals("Logged to memory: \"i parked on level 3\".", say("remember that I parked on level 3"))
        assertTrue(say("what do you remember")!!.contains("parked on level 3"))
    }
    @Test fun openUnknownApp() = assertTrue(say("open zzqqxx")!!.contains("couldn't locate"))
    @Test fun settingsPanel() = assertEquals("Opening Wi-Fi settings.", say("open wifi settings"))
    @Test fun media() = assertEquals("Next track.", say("skip track"))
    @Test fun browserSearch() = assertNotNull(skills.handle("search the web for best pizza near me")?.action)
    // Plain "search for X" is answered aloud by WebKnowledge instead of opening the browser.
    @Test fun plainSearchGoesToWeb() = assertNull(skills.handle("search for best pizza near me"))
    @Test fun openEndedFallsThroughToLlm() = assertNull(skills.handle("explain quantum entanglement like I'm five"))
}
