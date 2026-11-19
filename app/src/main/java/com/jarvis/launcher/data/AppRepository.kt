package com.jarvis.launcher.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

data class AppEntry(
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
) {
    val key: String get() = component.flattenToString()
}

/** Installed launchable apps, kept live via LauncherApps callbacks. */
class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val iconCache = LruCache<String, ImageBitmap>(400)
    private val infoByKey = HashMap<String, LauncherActivityInfo>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    init {
        refresh()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refresh()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = refresh()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) {
                iconCache.evictAll(); refresh()
            }
            override fun onPackagesAvailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = refresh()
            override fun onPackagesUnavailable(p: Array<out String>?, u: UserHandle?, r: Boolean) = refresh()
        })
    }

    fun refresh() {
        scope.launch {
            val profiles = runCatching { launcherApps.profiles }.getOrDefault(listOf(Process.myUserHandle()))
            val list = ArrayList<AppEntry>()
            synchronized(infoByKey) {
                infoByKey.clear()
                for (user in profiles) {
                    for (info in launcherApps.getActivityList(null, user)) {
                        if (info.componentName.packageName == context.packageName) continue
                        val entry = AppEntry(info.label.toString(), info.componentName.packageName, info.componentName, user)
                        infoByKey[entry.key] = info
                        list += entry
                    }
                }
            }
            _apps.value = list.sortedBy { it.label.lowercase() }
        }
    }

    suspend fun icon(entry: AppEntry, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache.get(entry.key)?.let { return@withContext it }
        val info = synchronized(infoByKey) { infoByKey[entry.key] } ?: return@withContext null
        runCatching {
            val d = info.getBadgedIcon(context.resources.displayMetrics.densityDpi)
            d.toBitmap(sizePx, sizePx).asImageBitmap()
        }.getOrNull()?.also { iconCache.put(entry.key, it) }
    }

    fun launch(entry: AppEntry, bounds: Rect? = null): Boolean = try {
        launcherApps.startMainActivity(entry.component, entry.user, bounds, null)
        true
    } catch (e: Exception) {
        false
    }

    fun openInfo(entry: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null) }
            .onFailure {
                startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${entry.packageName}")))
            }
    }

    fun uninstall(entry: AppEntry) {
        startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}")))
    }

    /** Sensible first-run dock: phone, messages, camera, browser, settings-ish. */
    fun defaultDock(): List<AppEntry> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
        )
        val list = _apps.value
        val result = intents.mapNotNull { i ->
            val pkg = runCatching { pm.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull()
            list.firstOrNull { it.packageName == pkg }
        }.distinctBy { it.packageName }
        return if (result.size >= 3) result else (result + list).distinctBy { it.packageName }.take(5)
    }

    fun byKey(key: String): AppEntry? = _apps.value.firstOrNull { it.key == key }

    /** Fuzzy app lookup used by the assistant ("open spotify", "launch the camera"). */
    fun findBest(query: String): AppEntry? {
        val q = normalize(query).removePrefix("the ").removeSuffix(" app").trim()
        if (q.isEmpty()) return null
        val apps = _apps.value
        apps.firstOrNull { normalize(it.label) == q }?.let { return it }
        apps.firstOrNull { normalize(it.label).startsWith(q) }?.let { return it }
        apps.firstOrNull { normalize(it.label).split(' ').any { w -> w.startsWith(q) } }?.let { return it }
        apps.firstOrNull { normalize(it.label).contains(q) }?.let { return it }
        apps.firstOrNull { it.packageName.lowercase().contains(q.replace(" ", "")) }?.let { return it }
        // Edit-distance fallback for misheard speech ("spotty fly" -> "Spotify").
        val scored = apps.map { it to levenshtein(normalize(it.label).replace(" ", ""), q.replace(" ", "")) }
        val best = scored.minByOrNull { it.second } ?: return null
        val tolerance = (q.length / 3).coerceAtLeast(1)
        return if (best.second <= tolerance) best.first else null
    }

    fun startSafely(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .replace(Regex("[^a-z0-9 ]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
