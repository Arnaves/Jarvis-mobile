package com.jarvis.launcher.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.jarvis.launcher.data.AppRepository
import java.util.Locale

data class Contact(val name: String, val number: String)

/** Fuzzy contact lookup tolerant of speech ("mummy kolkata" -> "Mummy (Kolkata)"). */
class Contacts(private val context: Context) {

    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(spoken: String): Contact? {
        if (!granted) return null
        val all = runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY),
                null, null, "${Phone.IS_SUPER_PRIMARY} DESC",
            )?.use { c ->
                buildList { while (c.moveToNext()) add(Contact(c.getString(0) ?: "", c.getString(1) ?: "")) }
            }
        }.getOrNull().orEmpty().filter { it.number.isNotBlank() }
        return best(spoken, all)
    }

    /** International format without "+", as WhatsApp expects (e.g. 919876543210). */
    fun international(number: String): String {
        val iso = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.let { it.simCountryIso.ifBlank { it.networkCountryIso } }
        }.getOrNull()?.uppercase(Locale.US)?.ifBlank { null } ?: Locale.getDefault().country
        val e164 = runCatching { PhoneNumberUtils.formatNumberToE164(number, iso) }.getOrNull()
        return (e164 ?: number).replace(Regex("[^0-9]"), "")
    }

    companion object {
        /** Picks the best-matching contact for a spoken name; pure so it can be unit-tested. */
        /** Lower-case, punctuation-free, any script (Devanagari, Bengali… names survive). */
        fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKC)
            .replace(Regex("[^\\p{L}\\p{N}\\p{M} ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun best(spoken: String, contacts: List<Contact>): Contact? {
            val q = norm(spoken).removePrefix("my ").trim()
            if (q.isEmpty()) return null
            val qTokens = q.split(' ')
            var bestScore = 0.0
            var best: Contact? = null
            for (c in contacts) {
                val n = norm(c.name)
                if (n.isEmpty()) continue
                val nTokens = n.split(' ')
                val score = when {
                    n == q -> 100.0
                    n.replace(" ", "") == q.replace(" ", "") -> 95.0
                    qTokens.all { qt -> nTokens.any { it.startsWith(qt) } } -> 80.0 + 10.0 * qTokens.size / nTokens.size
                    else -> {
                        val dist = AppRepository.levenshtein(n.replace(" ", ""), q.replace(" ", ""))
                        val tol = (q.length / 4).coerceAtLeast(1)
                        if (dist <= tol) 60.0 - dist else {
                            val hits = qTokens.count { qt -> nTokens.any { it.startsWith(qt) || AppRepository.levenshtein(it, qt) <= 1 } }
                            if (hits > 0 && hits >= (qTokens.size + 1) / 2) 30.0 + 10.0 * hits / qTokens.size else 0.0
                        }
                    }
                }
                if (score > bestScore) { bestScore = score; best = c }
            }
            return if (bestScore >= 30.0) best else null
        }
    }
}
