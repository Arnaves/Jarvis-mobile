package com.jarvis.launcher.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.jarvis.launcher.JarvisApp
import com.jarvis.launcher.MainActivity
import com.jarvis.launcher.R
import com.jarvis.launcher.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Place(val name: String, val lat: Double, val lon: Double, val radiusM: Float = 150f)

data class AutoTask(val id: String, val command: String, val trigger: Trigger, val createdAt: Long) {
    fun describe(): String = when (trigger) {
        is Trigger.Arrive -> "when you reach ${trigger.place}: $command"
        is Trigger.Leave -> "when you leave ${trigger.place}: $command"
        is Trigger.At -> "${SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(trigger.epochMs))}: $command"
    }
}

/**
 * Deferred tasks: "when I reach office, WhatsApp mummy…", "at 6 pm call dad", "remind me in 10 minutes…".
 * Location triggers use Play-services geofences; time triggers use AlarmManager. Both survive reboot.
 */
class TaskEngine(private val context: Context, private val prefs: Prefs) {
    private val sp = context.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE)
    private val _tasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<AutoTask>> = _tasks.asStateFlow()
    private val _places = MutableStateFlow(loadPlaces())
    val places: StateFlow<List<Place>> = _places.asStateFlow()
    private val main = Handler(Looper.getMainLooper())

    /** Runs a command through the assistant (set by JarvisApp). */
    var executor: ((String) -> Unit)? = null
    var speak: ((String) -> Unit)? = null

    private val title get() = prefs.userTitle.value

    // ------------------------------------------------------------------ voice entry points

    /** Handles place / task management phrases. Returns null if [raw] isn't one. */
    fun meta(raw: String): (suspend () -> Reply)? {
        val q = raw.lowercase().replace(Regex("^(hey |ok |okay )?jarvis[,.!]?\\s*"), "").replace(Regex("[?!.]+$"), "").trim()
        Regex("^(?:save|remember|mark|store|set) (?:this|my current|the current|current|this current)? ?(?:place|location|spot|position)? ?as (?:my |the )?(.+)$").find(q)
            ?.let { m -> return { savePlaceHere(m.groupValues[1].trim()) } }
        Regex("^(?:this is|i am at|i'm at|we are at) (?:my |the )(.+?)(?: now)?$").find(q)
            ?.let { m -> return { savePlaceHere(m.groupValues[1].trim()) } }
        Regex("^set (?:my |the )?(.+?) location(?: here| to here| to this place)?$").find(q)
            ?.let { m -> return { savePlaceHere(m.groupValues[1].trim()) } }
        if (Regex("^(what are |list |show )?(my )?(pending |scheduled )?tasks$|^what (tasks|have i asked you to do)").containsMatchIn(q)) {
            return {
                val list = _tasks.value
                Reply(if (list.isEmpty()) "No pending tasks, $title." else "You have ${list.size} pending: " + list.joinToString("; ") { it.describe() } + ".")
            }
        }
        if (Regex("^(cancel|clear|delete|remove) (all )?(my )?(pending )?tasks$").containsMatchIn(q)) {
            return { _tasks.value.forEach { cancel(it.id) }; Reply("All tasks cancelled.") }
        }
        Regex("^(?:cancel|delete|remove) (?:the )?task (?:about |for |to )?(.+)$").find(q)?.let { m ->
            return {
                val words = m.groupValues[1].split(' ').filter { it.length > 2 }
                val hit = _tasks.value.maxByOrNull { t -> words.count { t.command.contains(it) || t.describe().contains(it) } }
                if (hit == null || words.none { hit.describe().contains(it) }) Reply("I couldn't find that task.")
                else { cancel(hit.id); Reply("Cancelled: ${hit.describe()}.") }
            }
        }
        if (Regex("^(what|which) (places|locations) do you know|^(list|show) (my )?(places|locations)$").containsMatchIn(q)) {
            return {
                val p = _places.value
                Reply(if (p.isEmpty()) "No saved places yet. Say \"save this place as office\" when you're there." else "I know: ${p.joinToString { it.name }}.")
            }
        }
        return null
    }

    /** Schedules a parsed task and returns what JARVIS should say. */
    fun schedule(p: ParsedTask): Reply {
        val task = AutoTask(UUID.randomUUID().toString().take(8), p.command, p.trigger, System.currentTimeMillis())
        _tasks.value = _tasks.value + task
        saveTasks()
        return when (val t = p.trigger) {
            is Trigger.At -> {
                scheduleAlarm(task)
                Reply("Scheduled for ${SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(t.epochMs))}: ${p.command}.")
            }
            is Trigger.Arrive, is Trigger.Leave -> {
                val placeName = if (t is Trigger.Arrive) t.place else (t as Trigger.Leave).place
                val place = findPlace(placeName)
                when {
                    place == null -> Reply(
                        "Task saved, $title, but I don't know where \"$placeName\" is yet. " +
                            "When you're there, say \"save this place as $placeName\" and I'll start watching for it.",
                    )
                    !hasBackgroundLocation() -> Reply(
                        "Task saved. To trigger it automatically I need location access set to \"Allow all the time\".",
                        permission = Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                    else -> {
                        syncGeofences()
                        val verb = if (t is Trigger.Arrive) "reach" else "leave"
                        Reply("Understood. When you $verb ${place.name}, I'll ${p.command.removePrefix("remind me to ").let { if (p.command.startsWith("remind me")) "remind you to $it" else it }}.")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ places

    private suspend fun savePlaceHere(name: String): Reply {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Reply("I need precise location access to save places, $title.", permission = Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val loc = currentLocation() ?: return Reply("I couldn't get a location fix. Try again outdoors or with location turned on.")
        val clean = name.removePrefix("my ").removePrefix("the ").trim()
        _places.value = _places.value.filterNot { it.name == clean } + Place(clean, loc.latitude, loc.longitude)
        savePlaces()
        syncGeofences()
        val waiting = _tasks.value.count { (it.trigger as? Trigger.Arrive)?.place?.let(::matches)?.invoke(clean) == true || (it.trigger as? Trigger.Leave)?.place?.let(::matches)?.invoke(clean) == true }
        val bg = if (!hasBackgroundLocation()) " Set location to \"Allow all the time\" so I can watch it in the background." else ""
        return Reply("Saved this spot as $clean." + (if (waiting > 0) " $waiting task${if (waiting > 1) "s are" else " is"} now armed." else "") + bg)
    }

    fun deletePlace(name: String) {
        _places.value = _places.value.filterNot { it.name == name }
        savePlaces(); syncGeofences()
    }

    private fun matches(a: String): (String) -> Boolean = { b -> norm(a) == norm(b) }
    private fun norm(s: String) = s.lowercase().removePrefix("my ").removePrefix("the ").replace("work", "office").trim()
    private fun findPlace(name: String) = _places.value.firstOrNull { norm(it.name) == norm(name) }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): android.location.Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return withTimeoutOrNull(15_000) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() }.getOrNull()
        } ?: runCatching { client.lastLocation.await() }.getOrNull()
    }

    fun hasBackgroundLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && bg
    }

    // ------------------------------------------------------------------ geofences & alarms

    private fun geofenceIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, TaskReceiver::class.java).setAction(ACTION_GEOFENCE),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission")
    fun syncGeofences() {
        if (!hasBackgroundLocation()) return
        val client = LocationServices.getGeofencingClient(context)
        val pi = geofenceIntent()
        client.removeGeofences(pi).addOnCompleteListener {
            val needed = _tasks.value.mapNotNull {
                when (val t = it.trigger) { is Trigger.Arrive -> t.place; is Trigger.Leave -> t.place; else -> null }
            }.mapNotNull { findPlace(it) }.distinctBy { it.name }
            if (needed.isEmpty()) return@addOnCompleteListener
            val req = GeofencingRequest.Builder().setInitialTrigger(0).apply {
                needed.forEach { p ->
                    addGeofence(
                        Geofence.Builder().setRequestId(p.name).setCircularRegion(p.lat, p.lon, p.radiusM)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setNotificationResponsiveness(30_000)
                            .build()
                    )
                }
            }.build()
            runCatching { client.addGeofences(req, pi) }
        }
    }

    private fun alarmIntent(task: AutoTask) = PendingIntent.getBroadcast(
        context, task.id.hashCode(),
        Intent(context, TaskReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_ID, task.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleAlarm(task: AutoTask) {
        val at = (task.trigger as? Trigger.At)?.epochMs ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(task)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Re-arm everything (after boot or app update). */
    fun rearm() {
        val now = System.currentTimeMillis()
        _tasks.value.forEach { t ->
            val at = (t.trigger as? Trigger.At)?.epochMs
            if (at != null) { if (at <= now) fire(t) else scheduleAlarm(t) }
        }
        syncGeofences()
    }

    fun cancel(id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        if (t.trigger is Trigger.At) context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(t))
        _tasks.value = _tasks.value.filterNot { it.id == id }
        saveTasks(); syncGeofences()
    }

    // ------------------------------------------------------------------ firing

    fun onAlarm(id: String) { _tasks.value.firstOrNull { it.id == id }?.let(::fire) }

    fun onGeofence(placeIds: List<String>, entering: Boolean) {
        val hits = _tasks.value.filter { t ->
            when (val tr = t.trigger) {
                is Trigger.Arrive -> entering && placeIds.any { matches(it)(tr.place) }
                is Trigger.Leave -> !entering && placeIds.any { matches(it)(tr.place) }
                else -> false
            }
        }
        hits.forEach(::fire)
    }

    private fun fire(task: AutoTask) {
        _tasks.value = _tasks.value.filterNot { it.id == task.id }
        saveTasks()
        main.post {
            val reminder = Regex("^remind me (?:to |that |about )?(.+)$").find(task.command)?.groupValues?.get(1)
            val lead = when (val t = task.trigger) {
                is Trigger.Arrive -> "You've reached ${t.place}, $title."
                is Trigger.Leave -> "You've left ${t.place}, $title."
                is Trigger.At -> ""
            }
            if (reminder != null) {
                notify(task, "Reminder", reminder.replaceFirstChar { it.uppercase() }, runnable = false)
                speak?.invoke("$lead Reminder: $reminder.".trim())
                return@post
            }
            if (needsUnlockedUi(task.command) && !canShowUi()) {
                notify(task, "Ready: ${task.command}", "$lead Tap to run it now.".trim(), runnable = true)
                speak?.invoke("$lead I'm ready to ${task.command}. Unlock your phone and tap my notification.".trim())
            } else {
                if (lead.isNotEmpty()) speak?.invoke(lead)
                executor?.invoke(task.command)
                notify(task, "Done", task.command.replaceFirstChar { it.uppercase() }, runnable = false)
            }
        }
    }

    /** Commands that open another app's UI (WhatsApp, calls, maps) can't run behind the keyguard. */
    private fun needsUnlockedUi(cmd: String) =
        Regex("\\b(whatsapp|whats app|call|dial|ring|open|launch|navigate|play)\\b").containsMatchIn(cmd) ||
            (Regex("\\b(text|message|sms)\\b").containsMatchIn(cmd) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)

    private fun canShowUi(): Boolean {
        val unlocked = !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            context.getSystemService(PowerManager::class.java).isInteractive
        val mayStart = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(context)
        return unlocked && mayStart
    }

    private fun notify(task: AutoTask, title: String, text: String, runnable: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS tasks", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runnable) open.putExtra(EXTRA_RUN_COMMAND, task.command)
        val pi = PendingIntent.getActivity(context, task.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("JARVIS · $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .apply { if (runnable) addAction(0, "Run now", pi) }
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }

    // ------------------------------------------------------------------ persistence

    private fun saveTasks() {
        val arr = JSONArray()
        _tasks.value.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("cmd", t.command); put("at", t.createdAt)
                when (val tr = t.trigger) {
                    is Trigger.Arrive -> { put("type", "arrive"); put("place", tr.place) }
                    is Trigger.Leave -> { put("type", "leave"); put("place", tr.place) }
                    is Trigger.At -> { put("type", "time"); put("when", tr.epochMs) }
                }
            })
        }
        sp.edit().putString("tasks", arr.toString()).apply()
    }

    private fun loadTasks(): List<AutoTask> = runCatching {
        val arr = JSONArray(sp.getString("tasks", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val tr = when (o.getString("type")) {
                "arrive" -> Trigger.Arrive(o.getString("place"))
                "leave" -> Trigger.Leave(o.getString("place"))
                else -> Trigger.At(o.getLong("when"))
            }
            AutoTask(o.getString("id"), o.getString("cmd"), tr, o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun savePlaces() {
        val arr = JSONArray()
        _places.value.forEach { arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("r", it.radiusM.toDouble())) }
        sp.edit().putString("places", arr.toString()).apply()
    }

    private fun loadPlaces(): List<Place> = runCatching {
        val arr = JSONArray(sp.getString("places", "[]"))
        List(arr.length()) { i -> arr.getJSONObject(i).let { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.optDouble("r", 150.0).toFloat()) } }
    }.getOrDefault(emptyList())

    companion object {
        const val ACTION_GEOFENCE = "com.jarvis.launcher.GEOFENCE"
        const val ACTION_ALARM = "com.jarvis.launcher.TASK_ALARM"
        const val EXTRA_ID = "task_id"
        const val EXTRA_RUN_COMMAND = "run_command"
        private const val CHANNEL = "jarvis_tasks"
    }
}

/** Receives geofence transitions and task alarms. */
class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? JarvisApp ?: return
        when (intent.action) {
            TaskEngine.ACTION_ALARM -> intent.getStringExtra(TaskEngine.EXTRA_ID)?.let(app.tasks::onAlarm)
            TaskEngine.ACTION_GEOFENCE -> {
                val ev = GeofencingEvent.fromIntent(intent) ?: return
                if (ev.hasError()) return
                val ids = ev.triggeringGeofences?.map { it.requestId }.orEmpty()
                when (ev.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> app.tasks.onGeofence(ids, entering = true)
                    Geofence.GEOFENCE_TRANSITION_EXIT -> app.tasks.onGeofence(ids, entering = false)
                }
            }
        }
    }
}

/** Geofences and alarms are wiped on reboot; re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            (context.applicationContext as? JarvisApp)?.tasks?.rearm()
        }
    }
}
