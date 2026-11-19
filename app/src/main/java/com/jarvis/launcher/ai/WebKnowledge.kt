package com.jarvis.launcher.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Xml
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/** Result of a live lookup. [text] is what JARVIS says; [permission] asks the UI for access. */
data class WebAnswer(val text: String, val permission: String? = null, val fallbackSearch: String? = null)

/**
 * Live internet knowledge using free, key-less public APIs:
 * Open-Meteo (weather), Google News RSS (headlines), DuckDuckGo + Wikipedia (facts),
 * CoinGecko (crypto) and open.er-api.com (currency).
 */
class WebKnowledge(private val context: Context) {

    sealed interface Query {
        data class Weather(val city: String?, val tomorrow: Boolean) : Query
        data class News(val topic: String?) : Query
        data class Crypto(val coin: String) : Query
        data class Currency(val amount: Double, val from: String, val to: String) : Query
        data class Fact(val subject: String, val original: String) : Query
    }

    val online: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** Classifies [raw] into a live-data query, or null if it isn't one. */
    fun classify(raw: String): Query? {
        val q = raw.lowercase(Locale.US).replace(Regex("[?!.]+$"), "")
            .replace(Regex("^(hey |ok |okay )?jarvis[, ]*"), "").trim()

        if (Regex("\\b(weather|forecast|temperature outside|how (hot|cold) is it|is it (going to )?(rain|snow)|will it (rain|snow)|need an umbrella|humid)").containsMatchIn(q)) {
            val city = Regex("\\b(?:in|at|for) ([a-z][a-z .'-]{1,40}?)(?:\\s+(?:today|tomorrow|now|right now|this week|tonight))?$")
                .find(q)?.groupValues?.get(1)?.trim()?.takeUnless { it in setOf("today", "tomorrow", "the morning", "now") }
            return Query.Weather(city, q.contains("tomorrow"))
        }
        if (Regex("\\b(news|headlines|what's happening|what is happening in the world)\\b").containsMatchIn(q)) {
            val topic = Regex("(?:news|headlines) (?:about|on|for|from|in) (.+)$").find(q)?.groupValues?.get(1)
                ?: Regex("^(?:latest |today's |any )?(.+?) (?:news|headlines)$").find(q)?.groupValues?.get(1)
                    ?.takeUnless { it in setOf("the", "latest", "today's", "top", "any", "the latest", "world", "give me the", "tell me the", "what's the", "read me the") }
            return Query.News(topic?.removePrefix("the ")?.trim())
        }
        coins.entries.firstOrNull { (k, _) -> Regex("\\b$k\\b").containsMatchIn(q) }?.let { (_, id) ->
            if (Regex("\\b(price|worth|value|cost|trading|how much)\\b").containsMatchIn(q)) return Query.Crypto(id)
        }
        Regex("(?:convert |how much is |what is |what's )?(\\d+(?:\\.\\d+)?) ([a-z]+(?: [a-z]+)?) (?:to|in|into) ([a-z]+(?: [a-z]+)?)$").find(q)?.let { m ->
            val from = currency(m.groupValues[2]); val to = currency(m.groupValues[3])
            if (from != null && to != null) return Query.Currency(m.groupValues[1].toDouble(), from, to)
        }
        val factPattern = Regex(
            "^(?:who (?:is|was|are|were)|what (?:is|are|was|were)(?: an?| the)?|what's(?: an?| the)?|when (?:is|was|did)|where (?:is|was)|" +
                "tell me about|tell me something about|look up|search for|search|google|define|definition of|meaning of|explain|how (?:tall|old|big|far|many|much|long) (?:is|are|was))\\s+(.+)$"
        )
        factPattern.find(q)?.let { m ->
            val subject = m.groupValues[1].replace(Regex("^(the|a|an) "), "").trim()
            if (subject.length >= 2) return Query.Fact(subject, raw)
        }
        if (Regex("^(why|how|when|where|which|who|what)\\b").containsMatchIn(q) && q.split(' ').size >= 3) {
            return Query.Fact(q, raw)
        }
        return null
    }

    suspend fun answer(query: Query, title: String): WebAnswer = withContext(Dispatchers.IO) {
        if (!online) return@withContext WebAnswer("I'm cut off from the network at the moment, $title.")
        val result = withTimeoutOrNull(12_000) {
            runCatching {
                when (query) {
                    is Query.Weather -> weather(query, title)
                    is Query.News -> news(query)
                    is Query.Crypto -> crypto(query)
                    is Query.Currency -> convert(query)
                    is Query.Fact -> fact(query.subject)?.let { WebAnswer(it) }
                        ?: WebAnswer("I couldn't find a reliable answer to that, $title. Opening a search.", fallbackSearch = query.subject)
                }
            }.getOrElse { WebAnswer("The uplink failed: ${it.message ?: "unknown error"}.") }
        }
        result ?: WebAnswer("The network is responding slowly, $title. Try again in a moment.")
    }

    /** Short factual snippet to ground the LLM, or null. */
    suspend fun snippet(subject: String): String? = withContext(Dispatchers.IO) {
        if (!online) return@withContext null
        withTimeoutOrNull(5_000) { runCatching { fact(subject) }.getOrNull() }
    }

    // ------------------------------------------------------------------ weather

    private fun weather(q: Query.Weather, title: String): WebAnswer {
        val (lat, lon, place) = if (q.city != null) {
            val geo = json("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + Uri.encode(q.city))
            val r = geo.optJSONArray("results")?.optJSONObject(0)
                ?: return WebAnswer("I couldn't locate ${q.city} on the map, $title.")
            Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", q.city))
        } else {
            val loc = lastLocation()
                ?: return WebAnswer(
                    "I need your location for local weather, $title, or name a city: \"weather in London\".",
                    permission = Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            Triple(loc.latitude, loc.longitude, null)
        }
        val w = json(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2"
        )
        val where = place?.let { " in $it" } ?: ""
        val daily = w.getJSONObject("daily")
        if (q.tomorrow) {
            val code = daily.getJSONArray("weather_code").getInt(1)
            val hi = daily.getJSONArray("temperature_2m_max").getDouble(1).roundToInt()
            val lo = daily.getJSONArray("temperature_2m_min").getDouble(1).roundToInt()
            val rain = daily.getJSONArray("precipitation_probability_max").optInt(1, 0)
            return WebAnswer("Tomorrow$where: ${describe(code)}, high of $hi and low of $lo degrees, with a $rain percent chance of rain.")
        }
        val c = w.getJSONObject("current")
        val t = c.getDouble("temperature_2m").roundToInt()
        val feels = c.getDouble("apparent_temperature").roundToInt()
        val hum = c.optInt("relative_humidity_2m")
        val wind = c.optDouble("wind_speed_10m").roundToInt()
        val hi = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
        val lo = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(0, 0)
        val umbrella = if (rain >= 50) " I'd take an umbrella." else ""
        return WebAnswer(
            "Currently $t degrees$where and ${describe(c.getInt("weather_code"))}, feels like $feels. " +
                "Today's range is $lo to $hi, humidity $hum percent, wind $wind kilometres per hour, $rain percent chance of rain.$umbrella"
        )
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    private fun describe(code: Int) = when (code) {
        0 -> "clear skies"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzling"
        61, 63, 66 -> "raining"
        65, 67 -> "raining heavily"
        71, 73, 75, 77 -> "snowing"
        80, 81 -> "showery"
        82 -> "under violent showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "variable conditions"
    }

    // ------------------------------------------------------------------ news

    private fun news(q: Query.News): WebAnswer {
        val lang = Locale.getDefault()
        val gl = lang.country.ifBlank { "US" }
        val hl = "${lang.language}-$gl"
        val url = if (q.topic.isNullOrBlank()) "https://news.google.com/rss?hl=$hl&gl=$gl&ceid=$gl:${lang.language}"
        else "https://news.google.com/rss/search?q=${Uri.encode(q.topic)}&hl=$hl&gl=$gl&ceid=$gl:${lang.language}"
        val titles = rssTitles(get(url)).take(4)
        if (titles.isEmpty()) return WebAnswer("No headlines are coming through right now.")
        val intro = if (q.topic.isNullOrBlank()) "Top headlines." else "Latest on ${q.topic}."
        return WebAnswer(intro + " " + titles.mapIndexed { i, t -> "${i + 1}: $t." }.joinToString(" "))
    }

    private fun rssTitles(xml: String): List<String> {
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        val out = ArrayList<String>()
        var inItem = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                if (p.name == "item") inItem = true
                else if (inItem && p.name == "title") {
                    // Google News appends " - Publisher"; keep the headline only.
                    out += p.nextText().substringBeforeLast(" - ").trim()
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "item") inItem = false
        }
        return out
    }

    // ------------------------------------------------------------------ markets

    private val coins = linkedMapOf(
        "bitcoin" to "bitcoin", "btc" to "bitcoin", "ethereum" to "ethereum", "ether" to "ethereum", "eth" to "ethereum",
        "dogecoin" to "dogecoin", "doge" to "dogecoin", "solana" to "solana", "cardano" to "cardano",
        "xrp" to "ripple", "ripple" to "ripple", "litecoin" to "litecoin", "bnb" to "binancecoin",
    )

    private fun crypto(q: Query.Crypto): WebAnswer {
        val cur = localCurrency().lowercase()
        val j = json("https://api.coingecko.com/api/v3/simple/price?ids=${q.coin}&vs_currencies=$cur,usd&include_24hr_change=true")
            .getJSONObject(q.coin)
        val useCur = if (j.has(cur)) cur else "usd"
        val price = j.getDouble(useCur)
        val change = j.optDouble("${useCur}_24h_change", 0.0)
        val dir = if (change >= 0) "up" else "down"
        return WebAnswer(
            "${q.coin.replaceFirstChar { it.uppercase() }} is trading at ${money(price)} ${useCur.uppercase()}, " +
                "$dir ${"%.1f".format(kotlin.math.abs(change))} percent in the last 24 hours."
        )
    }

    private fun convert(q: Query.Currency): WebAnswer {
        val j = json("https://open.er-api.com/v6/latest/${q.from}")
        val rate = j.getJSONObject("rates").optDouble(q.to, Double.NaN)
        if (rate.isNaN()) return WebAnswer("I don't have a rate for ${q.to}.")
        return WebAnswer("${money(q.amount)} ${q.from} is ${money(q.amount * rate)} ${q.to}.")
    }

    private fun money(v: Double) = if (v >= 100) "%,.0f".format(v) else if (v >= 1) "%,.2f".format(v) else "%.4f".format(v)

    private fun localCurrency() = runCatching { java.util.Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD")

    private val currencyNames = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "bucks" to "USD", "euro" to "EUR", "euros" to "EUR",
        "pound" to "GBP", "pounds" to "GBP", "quid" to "GBP", "rupee" to "INR", "rupees" to "INR", "yen" to "JPY",
        "yuan" to "CNY", "won" to "KRW", "franc" to "CHF", "francs" to "CHF", "dirham" to "AED", "dirhams" to "AED",
        "canadian dollars" to "CAD", "australian dollars" to "AUD", "pesos" to "MXN", "rubles" to "RUB", "reais" to "BRL",
    )

    private fun currency(word: String): String? {
        val w = word.trim()
        currencyNames[w]?.let { return it }
        return if (Regex("^[a-z]{3}$").matches(w) && w !in setOf("the", "and", "for", "you")) w.uppercase() else null
    }

    // ------------------------------------------------------------------ facts

    private fun fact(subject: String): String? {
        // 1) DuckDuckGo instant answers (definitions, quick facts)
        runCatching {
            val d = json("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" + Uri.encode(subject))
            val answer = d.optString("Answer").takeIf { it.isNotBlank() && !it.startsWith("{") }
            val abstract = d.optString("AbstractText").takeIf { it.isNotBlank() }
            val definition = d.optString("Definition").takeIf { it.isNotBlank() }
            (answer ?: abstract ?: definition)?.let { return trimSentences(it) }
        }
        // 2) Wikipedia search, then summary of the best article
        val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
        val search = json(
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" + Uri.encode(subject)
        ).optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0)?.optString("title") ?: return null
        val summary = json("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(search.replace(' ', '_')))
        val extract = summary.optString("extract").takeIf { it.isNotBlank() } ?: return null
        return trimSentences(extract)
    }

    private fun trimSentences(s: String, max: Int = 2): String {
        val parts = s.replace(Regex("\\s*\\([^)]*\\)"), "").split(Regex("(?<=[.!?])\\s+"))
        return parts.take(max).joinToString(" ").take(420).trim()
    }

    // ------------------------------------------------------------------ http

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "JarvisLauncher/1.0 (Android; offline assistant)")
            c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun json(url: String) = JSONObject(get(url))
}
