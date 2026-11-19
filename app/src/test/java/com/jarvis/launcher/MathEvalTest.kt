package com.jarvis.launcher

import com.jarvis.launcher.ai.MathEval
import com.jarvis.launcher.ai.NumberWords
import com.jarvis.launcher.data.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MathEvalTest {
    @Test fun basicPrecedence() = assertEquals(14.0, MathEval.evaluate("2+3*4")!!, 1e-9)
    @Test fun parens() = assertEquals(20.0, MathEval.evaluate("(2+3)*4")!!, 1e-9)
    @Test fun power() = assertEquals(256.0, MathEval.evaluate("2^2^3")!!, 1e-9)
    @Test fun spoken() = assertEquals(17.0, MathEval.evaluateSpoken("five times 3 plus two")!!, 1e-9)
    @Test fun percent() = assertEquals(43.2, MathEval.evaluateSpoken("18% of 240")!!, 1e-9)
    @Test fun sqrt() = assertEquals(12.0, MathEval.evaluateSpoken("square root of 144")!!, 1e-9)
    @Test fun notMaths() = assertNull(MathEval.evaluateSpoken("what is the meaning of life"))
    @Test fun divideByZero() = assertNull(MathEval.evaluate("1/0"))
    @Test fun format() { assertEquals("17", MathEval.format(17.0)); assertEquals("0.5", MathEval.format(0.5)) }
    @Test fun numberWords() { assertEquals(25, NumberWords.parse("twenty five")); assertEquals(1, NumberWords.parse("a")) }
    @Test fun levenshtein() = assertEquals(3, AppRepository.levenshtein("kitten", "sitting"))
}

class MathEvalWordBoundaryTest {
    @Test fun noFalseOperators() = assertNull(MathEval.evaluateSpoken("tell me about model 3 addresses"))
    @Test fun lettersIgnored() = assertNull(MathEval.evaluateSpoken("explain quantum entanglement like i'm five"))
}
