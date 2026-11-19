package com.jarvis.launcher.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bookings and appointments. Android does not let apps speak into a phone call, so JARVIS
 * does everything around the call: finds the place (contacts, then OpenStreetMap near you),
 * dials it or opens its booking page, and puts a ready-to-read script on screen.
 */
class Concierge(
    private val context: Context,
    private val prefs: Prefs,
    private val contacts: Contacts,
    private val apps: AppRepository,
) {
    sealed interface Request {
        val place: String
        data class Table(override val place: String, val party: Int, val time: String?) : Request
        data class Appointment(override val place: String, val purpose: String?) : Request
    }

    fun classify(raw: String): Request? {
        val q = raw.lowercase().replace(Regex("^(hey |ok |okay )?jarvis[,.!]?\\s*"), "")
            .replace(Regex("\\b(please|kindly|can you|could you)\\b"), " ").replace(Regex("[?!.]+$"), "")
            .replace(Regex("\\s+"), " ").trim()
        Regex("^(?:book|reserve|get|grab)(?: me| us)? (?:a )?table for ([a-z0-9]+)(?: people| persons| guests| of us| pax)? (?:at|in) (.+?)(?:\\s+(?:at|for|by) (\\d{1,2}(?:[:.]\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)?(?: (?:tonight|today|tomorrow))?)|\\s+(tonight|today|tomorrow(?: night| evening| afternoon)?))?$")
            .find(q)?.let { m ->
                val party = NumberWords.parse(m.groupValues[1]) ?: 2
                val time = m.groupValues[3].ifBlank { m.groupValues[4] }.ifBlank { null }
                return Request.Table(m.groupValues[2].trim(), party, time)
            }
        Regex("^(?:call|phone|ring|contact) (.+?) and (?:take|book|make|get|fix|schedule|set up|arrange) (?:me )?(?:an |a )?(?:appointment|booking|reservation|slot|consultation)(?: for (.+))?$")
            .find(q)?.let { m -> return Request.Appointment(m.groupValues[1].trim(), m.groupValues[2].ifBlank { null }) }
        Regex("^(?:book|make|schedule|get|fix) (?:me )?(?:an |a )?(?:appointment|consultation|slot) (?:with|at) (.+?)(?: for (.+))?$")
            .find(q)?.let { m -> return Request.Appointment(m.groupValues[1].trim(), m.groupValues[2].ifBlank { null }) }
        return null
    }

    data class Lookup(val name: String, val phone: String?, val website: String?)

    suspend fun handle(r: Request): Reply {
        val title = prefs.userTitle.value
        val found = contacts.find(r.place)?.let { Lookup(it.name, it.number, null) } ?: lookupWeb(r.place)
        val owner = prefs.ownerName.value.ifBlank { "[your name]" }
        val script = when (r) {
            is Request.Table -> "\"Hello! I'd like to book a table for ${r.party}${r.time?.let { " at $it" } ?: " tonight"}, under the name $owner. Could you confirm?\""
            is Request.Appointment -> "\"Hello, I'd like to book an appointment${r.purpose?.let { " for $it" } ?: ""}. What's your earliest availability? My name is $owner.\""
        }
        val what = if (r is Request.Table) "a table for ${r.party}" else "an appointment"
        return when {
            found?.phone != null -> Reply(
                "Dialling ${found.name} for $what, $title. I can't speak on the call myself, so here's your script: $script",
                leavesLauncher = true,
                action = { apps.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(found.phone)))) },
            )
            found?.website != null -> Reply(
                "${found.name} doesn't list a phone number, so I'm opening their website to book $what.",
                leavesLauncher = true,
                action = { apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(found.website))) },
            )
            else -> Reply(
                "I couldn't find contact details for ${r.place}. Opening Maps: use its Reserve or Call button. Script: $script",
                leavesLauncher = true,
                action = { apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(r.place)))) },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lookupWeb(place: String): Lookup? = withContext(Dispatchers.IO) {
        runCatching {
            val loc = if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val lm = context.getSystemService(android.location.LocationManager::class.java)
                lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            } else null
            val base = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&extratags=1&q=" + Uri.encode(place)
            val near = loc?.let { "&viewbox=${it.longitude - 0.3},${it.latitude + 0.3},${it.longitude + 0.3},${it.latitude - 0.3}&bounded=1" }
            val arr = (near?.let { get(base + it) }?.let(::JSONArray)?.takeIf { it.length() > 0 }) ?: JSONArray(get(base))
            val o = arr.optJSONObject(0) ?: return@runCatching null
            val tags = o.optJSONObject("extratags")
            val phone = tags?.optString("phone")?.ifBlank { null } ?: tags?.optString("contact:phone")?.ifBlank { null }
            val site = tags?.optString("website")?.ifBlank { null } ?: tags?.optString("contact:website")?.ifBlank { null }
            Lookup(o.optString("name").ifBlank { place }, phone?.substringBefore(';')?.trim(), site)
        }.getOrNull()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "JarvisLauncher/1.0 (Android voice assistant)")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
