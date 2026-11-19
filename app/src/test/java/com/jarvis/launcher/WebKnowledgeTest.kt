package com.jarvis.launcher

import androidx.test.core.app.ApplicationProvider
import com.jarvis.launcher.ai.WebKnowledge
import com.jarvis.launcher.ai.WebKnowledge.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebKnowledgeTest {
    private val web = WebKnowledge(ApplicationProvider.getApplicationContext())

    @Test fun localWeather() = assertEquals(Query.Weather(null, false), web.classify("What's the weather like?"))
    @Test fun cityWeather() = assertEquals(Query.Weather("paris", false), web.classify("weather in Paris"))
    @Test fun tomorrowRain() = assertEquals(Query.Weather("london", true), web.classify("will it rain in london tomorrow"))
    @Test fun headlines() = assertEquals(Query.News(null), web.classify("give me the latest news"))
    @Test fun topicNews() = assertEquals(Query.News("tesla"), web.classify("news about Tesla"))
    @Test fun crypto() = assertEquals(Query.Crypto("bitcoin"), web.classify("what's the bitcoin price"))
    @Test fun currency() = assertEquals(Query.Currency(100.0, "USD", "INR"), web.classify("convert 100 dollars to rupees"))
    @Test fun currencyCodes() = assertEquals(Query.Currency(50.0, "EUR", "GBP"), web.classify("how much is 50 eur in gbp"))
    @Test fun who() = assertEquals("ada lovelace", (web.classify("Who was Ada Lovelace?") as Query.Fact).subject)
    @Test fun lookup() = assertEquals("black holes", (web.classify("look up black holes") as Query.Fact).subject)
    @Test fun openQuestion() = assertTrue(web.classify("why is the sky blue") is Query.Fact)
    @Test fun notWeb() = assertNull(web.classify("flashlight on"))
}
