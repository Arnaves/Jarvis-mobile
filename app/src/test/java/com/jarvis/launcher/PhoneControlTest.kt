package com.jarvis.launcher

import android.accessibilityservice.AccessibilityService
import com.jarvis.launcher.ai.Roman
import com.jarvis.launcher.ai.ScreenSkills
import com.jarvis.launcher.ai.ScreenSkills.Cmd
import com.jarvis.launcher.ai.WhatsAppFlow
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
class PhoneControlTest {
    private fun c(s: String) = ScreenSkills.classify(s)

    @Test fun back() = assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, (c("go back") as Cmd.Global).action)
    @Test fun home() = assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, (c("go home") as Cmd.Global).action)
    @Test fun notifications() = assertEquals(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, (c("open notifications") as Cmd.Global).action)
    @Test fun lock() = assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, (c("lock the phone") as Cmd.Global).action)
    @Test fun screenshot() = assertEquals(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, (c("take a screenshot") as Cmd.Global).action)
    @Test fun read() = assertEquals(Cmd.Read, c("read the screen"))
    @Test fun tap() = assertEquals(Cmd.Tap("sign in"), c("tap on the sign in button"))
    @Test fun type() = assertEquals(Cmd.Type("kya kar rahe ho"), c("type kya kar rahe ho"))
    @Test fun scroll() = assertEquals(Cmd.Scroll(true), c("scroll down"))
    @Test fun swipe() = assertEquals(Cmd.Swipe("left"), c("swipe left"))
    @Test fun notScreen() = assertNull(c("what's the weather"))

    @Test fun splitNames() = assertEquals(listOf("rahul", "priya", "amit"), WhatsAppFlow.splitNames("rahul, priya and amit"))

    @Test fun hindiToRoman() = assertEquals("kya kar rahe ho", Roman.toRoman("क्या कर रहे हो"))
    @Test fun englishUntouched() = assertEquals("What are you doing?", Roman.toRoman("What are you doing?"))
    @Test fun detectsScript() { assertTrue(Roman.needs("नमस्ते")); assertFalse(Roman.needs("namaste")) }
}
