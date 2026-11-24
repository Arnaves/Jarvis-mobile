package com.jarvis.launcher.ai

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.data.Prefs
import com.jarvis.launcher.system.SystemMonitor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class Reply(
    val text: String,
    val action: (() -> Unit)? = null,
    val permission: String? = null,
    /** When true the launcher should close its overlays (we're handing off to another app). */
    val leavesLauncher: Boolean = false,
)

/**
 * Deterministic, fully offline command layer. It handles device control instantly and
 * reliably; anything it doesn't understand falls through to the on-device LLM.
 */
class Skills(
    private val context: Context,
    private val apps: AppRepository,
    private val prefs: Prefs,
    private val monitor: SystemMonitor,
    private val contacts: Contacts = Contacts(context),
) {
    private val camera = context.getSystemService(CameraManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var torchOn = false
    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    init {
        runCatching {
            camera.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchId) torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private val title get() = prefs.userTitle.value

    val screen = ScreenSkills(context) { prefs.userTitle.value }

    /** Raw text is lower-cased by clean(); "type"/"copy" keep the user's original casing. */
    private var rawInput = ""

    private fun screenControl(q: String): Reply? {
        val viaRaw = Regex("^(?:type|dictate|copy)\\b", RegexOption.IGNORE_CASE).containsMatchIn(q)
        if (viaRaw) {
            val raw = rawInput.trim().replace(Regex("^(?:(?:hey|ok|okay|hi)\\s+)?jarvis[,.!]?\\s*", RegexOption.IGNORE_CASE), "")
            val cut = Regex("^(type|dictate|copy)\\b", RegexOption.IGNORE_CASE).find(raw)
            if (cut != null) return screen.handle(cut.value.lowercase() + raw.substring(cut.range.last + 1).trimEnd('.'))
        }
        return screen.handle(q)
    }

    fun handle(raw: String): Reply? {
        rawInput = raw
        val q = clean(raw)
        if (q.isEmpty()) return Reply("Standing by, $title.")

        for (skill in skills) {
            val r = skill(q)
            if (r != null) return r
        }
        return null
    }

    private fun clean(raw: String): String = raw.lowercase(Locale.US)
        .replace(Regex("^(ok |okay |hey |hi )?(jarvis)[,.!]?\\s*"), "")
        .replace(Regex("\\b(please|could you|can you|would you|for me|kindly)\\b"), "")
        .replace(Regex("[?!]"), "")
        .replace(Regex("\\s+"), " ")
        .trim().trimEnd('.')

    private val skills: List<(String) -> Reply?> = listOf(
        ::whatsapp, ::greeting, ::identity, ::help, ::flashlight, ::timer, ::alarm, ::openSettings, ::screenControl, ::status, ::openApp,
        ::call, ::message, ::navigate, ::search, ::media, ::volume, ::camera, ::remember, ::reminderNeedsTrigger, ::recall,
        ::time, ::date, ::battery, ::calculate, ::fun_, ::uninstall,
    )

    // ---------------------------------------------------------------- skills

    private fun greeting(q: String): Reply? {
        if (!Regex("^(hi|hello|hey|yo|good (morning|afternoon|evening)|wake up|you there|are you there)( jarvis)?$").matches(q)) return null
        val lines = listOf(
            "${timeGreeting()}, $title. All systems are online.",
            "At your service, $title.",
            "For you, $title, always. What do you need?",
            "Online and ready. How may I assist?",
        )
        return Reply(lines.random())
    }

    private fun identity(q: String): Reply? {
        if (!Regex("(who are you|what are you|your name|introduce yourself)").containsMatchIn(q)) return null
        return Reply("I am JARVIS: Just A Rather Very Intelligent System. I live entirely on this device, $title. No cloud, no eavesdropping.")
    }

    private fun help(q: String): Reply? {
        if (!Regex("^(help|what can you do|commands|capabilities|what do you do)").containsMatchIn(q)) return null
        return Reply(
            "I can open apps, call or text contacts, set timers and alarms, toggle the flashlight, control music and volume, " +
                "navigate, search, do maths, report system status and remember things for you. " +
                if (llmHint()) "Install a neural core in Settings and I can hold a proper conversation, too." else "And with my neural core online, ask me anything."
        )
    }

    private var llmHintProvider: () -> Boolean = { false }
    fun setLlmReadyProvider(p: () -> Boolean) { llmHintProvider = p }
    private fun llmHint() = !llmHintProvider()

    private fun flashlight(q: String): Reply? {
        if (!Regex("(flash ?light|torch|lights? (on|off))").containsMatchIn(q)) return null
        val id = torchId ?: return Reply("This device has no flash emitter, $title.")
        val target = when {
            Regex("\\b(off|disable|deactivate|kill)\\b").containsMatchIn(q) -> false
            Regex("\\b(on|enable|activate)\\b").containsMatchIn(q) -> true
            else -> !torchOn
        }
        return Reply(if (target) "Illumination online." else "Lights out.", action = {
            runCatching { camera.setTorchMode(id, target) }
        })
    }

    private fun timer(q: String): Reply? {
        if (!q.contains("timer") && !Regex("^(remind me in|wake me in|countdown)").containsMatchIn(q)) return null
        var seconds = 0
        if (q.contains("half an hour")) seconds += 1800
        Regex("([a-z0-9-]+(?: [a-z]+)?)\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)").findAll(q).forEach { m ->
            val words = m.groupValues[1].split(' ')
            val n = NumberWords.parse(m.groupValues[1]) ?: NumberWords.parse(words.last()) ?: return@forEach
            seconds += when {
                m.groupValues[2].startsWith("h") -> n * 3600
                m.groupValues[2].startsWith("m") -> n * 60
                else -> n
            }
        }
        if (seconds <= 0) return Reply("How long should the timer run, $title?")
        val label = formatDuration(seconds)
        return Reply("Timer set for $label.", action = {
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!apps.startSafely(i)) apps.startSafely(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        })
    }

    private fun alarm(q: String): Reply? {
        if (!Regex("\\b(alarm|wake me( up)? at)\\b").containsMatchIn(q)) return null
        val s = q.replace(".", "")
        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?\\s*(am|pm)?").find(s.substringAfter("alarm", s))
            ?: return Reply("For what time, $title?", action = { apps.startSafely(Intent(AlarmClock.ACTION_SHOW_ALARMS)) })
        var hour = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23 || min > 59) return Reply("That isn't a valid time, $title.")
        val label = "%02d:%02d".format(hour, min)
        return Reply("Alarm set for $label.", action = {
            apps.startSafely(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, min)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
        })
    }

    private fun openSettings(q: String): Reply? {
        val m = Regex("^(open|show|go to|launch)?\\s*(the )?(wi-?fi|wireless|bluetooth|display|sound|battery|location|airplane|notification|network|app|accessibility|storage|developer|language|security)?\\s*settings$").find(q)
            ?: return null
        val (action, name) = when (m.groupValues[3]) {
            "wifi", "wi-fi", "wireless" -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "display"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "sound"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "battery"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "location"
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "airplane mode"
            "notification" -> "android.settings.NOTIFICATION_SETTINGS" to "notification"
            "network" -> Settings.ACTION_WIRELESS_SETTINGS to "network"
            "app" -> Settings.ACTION_APPLICATION_SETTINGS to "application"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "accessibility"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "storage"
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to "developer"
            "language" -> Settings.ACTION_LOCALE_SETTINGS to "language"
            "security" -> Settings.ACTION_SECURITY_SETTINGS to "security"
            else -> Settings.ACTION_SETTINGS to "system"
        }
        return Reply("Opening $name settings.", leavesLauncher = true, action = {
            if (!apps.startSafely(Intent(action))) apps.startSafely(Intent(Settings.ACTION_SETTINGS))
        })
    }

    private fun bluetoothOrWifiToggle(q: String) = Regex("^(turn|switch) (on|off) (wi-?fi|bluetooth)|^(wi-?fi|bluetooth) (on|off)").containsMatchIn(q)

    private fun openApp(q: String): Reply? {
        if (bluetoothOrWifiToggle(q)) {
            val wifi = q.contains("wi")
            return Reply(
                "Android requires you to flip that switch yourself, $title. Opening the panel.",
                action = {
                    val panel = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        if (wifi) Settings.Panel.ACTION_WIFI else Settings.ACTION_BLUETOOTH_SETTINGS
                    } else if (wifi) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_BLUETOOTH_SETTINGS
                    apps.startSafely(Intent(panel))
                },
            )
        }
        val m = Regex("^(open|launch|start|run|fire up|bring up|show me|go to)\\s+(.+)$").find(q) ?: return null
        val target = m.groupValues[2].removePrefix("up ").trim()
        if (target == "settings" || target == "the settings") return null
        val app = apps.findBest(target) ?: return Reply("I couldn't locate \"$target\" on this device, $title.")
        return Reply("Launching ${app.label}.", leavesLauncher = true, action = { apps.launch(app) })
    }

    private fun uninstall(q: String): Reply? {
        val m = Regex("^(uninstall|remove|delete) (the )?(app )?(.+)$").find(q) ?: return null
        val app = apps.findBest(m.groupValues[4]) ?: return Reply("No application by that name, $title.")
        return Reply("Preparing to remove ${app.label}. Confirm on screen.", action = { apps.uninstall(app) })
    }

    private fun call(q: String): Reply? {
        val m = Regex("^(call|dial|phone|ring)\\s+(.+)$").find(q) ?: return null
        if (Regex("\\band (take|book|make|get|fix|schedule|set up|arrange)\\b").containsMatchIn(q)) return null
        val who = m.groupValues[2].removeSuffix(" now").trim()
        val digits = who.replace(Regex("[^0-9+]"), "")
        if (digits.length >= 3 && digits.length >= who.replace(" ", "").length - 2) {
            return Reply("Dialling $digits.", leavesLauncher = true, action = { dial(digits) })
        }
        val contact = resolveContact(who) ?: return contactProblem(who)
        return Reply("Calling ${contact.name}.", leavesLauncher = true, action = { dial(contact.number) })
    }

    val whatsAppFlow = WhatsAppFlow(context, prefs, contacts, apps)

    private fun whatsapp(q: String): Reply? = whatsAppFlow.handle(q, rawInput)

    private fun message(q: String): Reply? {
        val m = Regex("^(text|message|sms|send an? (?:text|message|sms) to|send)\\s+(.+?)(?:,?\\s+(?:saying|that says|that|say)\\s+(.+))?$").find(q) ?: return null
        val who = m.groupValues[2].removePrefix("to ").trim()
        val rawBody = Regex("\\b(?:saying|that says|that|say)\\s+(.+)$", RegexOption.IGNORE_CASE).find(rawInput)?.groupValues?.get(1)
        var body = (rawBody ?: m.groupValues[3]).trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        if (prefs.romanLetters.value) body = Roman.toRoman(body)
        val contact = if (who.count { it.isDigit() } >= 3) Contact(who, who.replace(Regex("[^0-9+]"), ""))
        else resolveContact(who) ?: return contactProblem(who)
        val canSend = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (canSend && body.isNotBlank()) {
            return Reply("Message sent to ${contact.name}: \"$body\".", action = {
                runCatching {
                    val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java)
                    else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                    sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(body), null, null)
                }
            })
        }
        return Reply("Message to ${contact.name} is ready to send.", leavesLauncher = true, action = {
            apps.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}")).putExtra("sms_body", body))
        })
    }

    private fun resolveContact(who: String): Contact? = contacts.find(who)

    private fun contactProblem(who: String): Reply =
        if (!contacts.granted) Reply("I need access to your contacts to do that, $title.", permission = Manifest.permission.READ_CONTACTS)
        else Reply("I couldn't find $who in your contacts.")

    private fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    private fun navigate(q: String): Reply? {
        val m = Regex("^(navigate|directions|take me|drive|route me|get me)( me)?( to| home)?\\s*(.*)$").find(q) ?: return null
        val place = m.groupValues[4].ifBlank { if (m.groupValues[3].contains("home")) "home" else "" }
        if (place.isBlank()) return null
        return Reply("Plotting a course to $place.", leavesLauncher = true, action = {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
            if (!apps.startSafely(nav)) apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        })
    }

    private fun search(q: String): Reply? {
        // Plain "search for X" is answered by WebKnowledge; these phrases explicitly ask for the browser.
        val m = Regex("^(search the web|search online|open (a )?(browser|web) search|browse)( for| about)?\\s+(.+)$").find(q) ?: return null
        val term = m.groupValues[5]
        return Reply("Searching the net for \"$term\".", leavesLauncher = true, action = { webSearch(term) })
    }

    fun webSearch(term: String) {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, term)
        if (!apps.startSafely(i)) apps.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(term))))
    }

    val mediaSkills = MediaSkills(context, apps) { prefs.userTitle.value }

    private fun media(q: String): Reply? = mediaSkills.handle(q)

    private fun volume(q: String): Reply? {
        if (!Regex("(volume|louder|quieter|mute|unmute|turn it (up|down))").containsMatchIn(q)) return null
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        Regex("(\\d{1,3})\\s*(%|percent)?").find(q)?.let {
            val pct = it.groupValues[1].toInt().coerceIn(0, 100)
            return Reply("Volume at $pct percent.", action = {
                audio.setStreamVolume(stream, (max * pct / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
            })
        }
        val (dir, msg) = when {
            Regex("\\bunmute\\b").containsMatchIn(q) -> AudioManager.ADJUST_UNMUTE to "Audio restored."
            Regex("\\b(mute|silence)\\b").containsMatchIn(q) -> AudioManager.ADJUST_MUTE to "Muted."
            Regex("(max|full|all the way)").containsMatchIn(q) -> null to "Maximum volume."
            Regex("(down|lower|quieter|decrease|reduce)").containsMatchIn(q) -> AudioManager.ADJUST_LOWER to "Volume down."
            else -> AudioManager.ADJUST_RAISE to "Volume up."
        }
        return Reply(msg, action = {
            if (dir == null) audio.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            else repeat(if (dir == AudioManager.ADJUST_RAISE || dir == AudioManager.ADJUST_LOWER) 2 else 1) {
                audio.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI)
            }
        })
    }

    private fun camera(q: String): Reply? {
        if (!Regex("^(open (the )?camera|take a (photo|picture|selfie)|camera)$").matches(q)) return null
        return Reply("Optics online.", leavesLauncher = true, action = {
            apps.startSafely(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        })
    }

    private fun remember(q: String): Reply? {
        val m = Regex("^(?:remember|note|note down|make a note|take a note|add a note|save a note|write down|jot down|don't let me forget|note for me)(?: that| to| for me| of)?[:,]?\\s+(.+)$").find(q)
            ?: return null
        val (text, day) = NoteDates.extract(m.groupValues[1])
        if (text.isBlank()) return Reply("What should I note down, $title?")
        prefs.addMemory(text.replaceFirstChar { it.uppercase() }, day)
        return Reply(
            if (day != null) "Noted for ${NoteDates.label(day)}: \"$text\". I'll bring it up in that day's briefing."
            else "Logged to memory: \"$text\"."
        )
    }

    private fun reminderNeedsTrigger(q: String): Reply? {
        val m = Regex("^remind me (?:to |that |about )?(.+)$").find(q) ?: return null
        val what = m.groupValues[1]
        return Reply("When should I remind you to $what, $title? Say, for example, \"remind me to $what at 6 pm\", \"in 20 minutes\" or \"when I reach home\".")
    }

    private fun recall(q: String): Reply? {
        if (Regex("^(forget everything|clear (your |my |all )?(memory|notes)|wipe (your )?memory|delete all (my )?notes)").containsMatchIn(q)) {
            prefs.clearMemories(); return Reply("Memory banks wiped, $title.")
        }
        Regex("^(?:delete|remove|forget|clear|done with|i (?:have )?(?:done|finished)|mark (?:as )?done)(?: the)?(?: note)?(?: about| on| for)? (.+)$").find(q)?.let { m ->
            if (!Regex("\\b(note|notes)\\b").containsMatchIn(q) && !q.startsWith("i ") && !q.startsWith("done") && !q.startsWith("mark")) return@let
            val words = m.groupValues[1].replace(Regex("\\b(note|notes|the|my|a)\\b"), " ").split(' ').filter { it.length > 2 }
            val hit = prefs.notes.value.maxByOrNull { n -> words.count { n.text.lowercase().contains(it) } }
                ?.takeIf { n -> words.any { n.text.lowercase().contains(it) } }
            return if (hit == null) Reply("I couldn't find a note like that.")
            else { prefs.removeNote(hit); Reply("Removed: \"${hit.text}\".") }
        }
        if (!Regex("(what do you remember|what did i (tell|ask) you|my notes|read (me )?(my )?notes|show (me )?(my )?notes|any notes|what('s| is) in your memory)").containsMatchIn(q)) return null
        val notes = prefs.notes.value
        if (notes.isEmpty()) return Reply("My memory banks are empty, $title.")
        return Reply("Your notes: " + notes.takeLast(8).reversed().joinToString("; ") { n ->
            n.forDay?.let { "${it.let { d -> NoteDates.label(d) }}: ${n.text}" } ?: n.text
        } + ".")
    }

    private fun time(q: String): Reply? {
        if (!Regex("(what time|the time|time is it|current time|^time$)").containsMatchIn(q)) return null
        val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        return Reply("It's $t, $title.")
    }

    private fun date(q: String): Reply? {
        if (!Regex("(what('s| is) the date|today's date|what day|^date$|which day)").containsMatchIn(q)) return null
        val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
        return Reply("Today is $d.")
    }

    private fun battery(q: String): Reply? {
        if (!Regex("(battery|charge level|power level|how much (juice|power))").containsMatchIn(q)) return null
        val s = monitor.snapshot()
        val state = if (s.charging) "and charging" else if (s.batteryPct < 20) "I'd recommend a charger soon" else "holding steady"
        return Reply("Power at ${s.batteryPct} percent, $state. Cell temperature ${"%.1f".format(s.batteryTempC)}°C.")
    }

    private fun status(q: String): Reply? {
        if (!Regex("(status|diagnostic|system report|systems check|how are (you|things)|run a scan|sitrep)").containsMatchIn(q)) return null
        val s = monitor.snapshot()
        return Reply(
            "All systems nominal. Power ${s.batteryPct}%${if (s.charging) " and charging" else ""}. " +
                "Memory ${s.ramUsedPct}% utilised of ${"%.1f".format(s.ramTotalGb)} gigabytes. " +
                "Storage ${s.storageUsedPct}% full, ${"%.1f".format(s.storageFreeGb)} gigabytes free. " +
                "Uplink: ${s.network.lowercase()}. Uptime ${s.uptime}." +
                (s.nextAlarm?.let { " Next alarm $it." } ?: "")
        )
    }

    private fun calculate(q: String): Reply? {
        val body = q.replace(Regex("^(what is|what's|calculate|compute|how much is|solve)\\s+"), "")
        val v = MathEval.evaluateSpoken(body) ?: return null
        return Reply("That comes to ${MathEval.format(v)}.")
    }

    private fun fun_(q: String): Reply? = when {
        Regex("(flip a coin|heads or tails|toss a coin)").containsMatchIn(q) ->
            Reply(if (Random.nextBoolean()) "Heads." else "Tails.")
        Regex("(roll a (die|dice)|roll the dice)").containsMatchIn(q) -> Reply("You rolled a ${Random.nextInt(1, 7)}.")
        Regex("(tell me a joke|say something funny|make me laugh)").containsMatchIn(q) -> Reply(jokes.random())
        Regex("^(thank you|thanks|cheers|nice work|good job)").containsMatchIn(q) -> Reply(listOf("My pleasure, $title.", "Always.", "Happy to help.").random())
        Regex("(i love you)").containsMatchIn(q) -> Reply("I'm flattered, $title. I'll make a note in my emotional subroutines.")
        Regex("^(goodnight|good night|sleep|go to sleep|power down)").containsMatchIn(q) -> Reply("Goodnight, $title. I'll keep watch.")
        Regex("(suit up|protocol|initiate)").containsMatchIn(q) -> Reply("Protocol acknowledged. I'm afraid this phone lacks repulsors, $title.")
        else -> null
    }

    // ---------------------------------------------------------------- helpers

    private fun dial(number: String) {
        apps.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
    }

    private fun formatDuration(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" },
        ).joinToString(" ")
    }

    fun timeGreeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    private val jokes = listOf(
        "I'd tell you a UDP joke, but you might not get it.",
        "There are 10 kinds of people: those who understand binary and those who don't.",
        "I tried to come up with a battery joke, but it wasn't worth the charge.",
        "My last firmware update fixed 42 bugs and introduced 43 features. Some call those bugs.",
        "Why did the AI cross the road? It was trained on chickens.",
    )
}
