package com.jarvis.launcher

import com.jarvis.launcher.ai.Brain
import org.junit.Assert.assertEquals
import org.junit.Test

class BrainsTest {
    @Test fun chat() = assertEquals(Brain.CHAT, Brain.route("tell me about the moon", false))
    @Test fun think() = assertEquals(Brain.THINK, Brain.route("should I buy or rent, pros and cons", false))
    @Test fun code() = assertEquals(Brain.CODE, Brain.route("write a python function to reverse a list", false))
    @Test fun vision() = assertEquals(Brain.VISION, Brain.route("what is this", true))
    @Test fun forced() = assertEquals(Brain.THINK, Brain.route("hello", false, Brain.THINK))
    @Test fun imageBeatsForced() = assertEquals(Brain.VISION, Brain.route("hello", true, Brain.CODE))

    @Test fun splitsThoughts() {
        val (t, a) = Brain.splitThoughts("<think>2+2 is 4</think>The answer is 4.")
        assertEquals("2+2 is 4", t); assertEquals("The answer is 4.", a)
    }
    @Test fun streamingThought() = assertEquals("" to "", Brain.splitThoughts("<think>").let { it.first to it.second })
    @Test fun speakableHidesCode() =
        assertEquals("Here you go: (code on screen) Done.", Brain.speakable("Here you go: ```kotlin\nfun a() = 1\n``` Done."))
}
