package com.jarvis.launcher.system

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SystemSnapshot(
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val batteryTempC: Float = 0f,
    val ramUsedPct: Int = 0,
    val ramTotalGb: Float = 0f,
    val storageUsedPct: Int = 0,
    val storageFreeGb: Float = 0f,
    val network: String = "OFFLINE",
    val uptime: String = "",
    val nextAlarm: String? = null,
    /** "AC", "USB", "WIRELESS", "DOCK" or null when unplugged. */
    val plug: String? = null,
    /** Milliseconds until full, or null if unknown. */
    val timeToFullMs: Long? = null,
    /** Approximate charging power in watts, or null if the device doesn't report it. */
    val watts: Float? = null,
)

class SystemMonitor(private val context: Context) {

    fun snapshot(): SystemSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramUsed = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0

        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes.toDouble()
        val free = stat.availableBytes.toDouble()
        val storagePct = if (total > 0) ((total - free) * 100 / total).toInt() else 0

        val up = SystemClock.elapsedRealtime() / 1000
        val uptime = "%dd %02dh %02dm".format(up / 86400, (up % 86400) / 3600, (up % 3600) / 60)

        val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let {
            SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(it.triggerTime))
        }

        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            8 /* BATTERY_PLUGGED_DOCK, API 33 */ -> "DOCK"
            else -> null
        }
        val bm = context.getSystemService(BatteryManager::class.java)
        val ttf = if (charging && android.os.Build.VERSION.SDK_INT >= 28) {
            bm?.computeChargeTimeRemaining()?.takeIf { it > 0 }
        } else null
        val watts = if (charging) runCatching {
            // CURRENT_NOW is µA on most devices (some report mA or negative values while charging).
            var ua = kotlin.math.abs(bm!!.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong())
            if (ua in 1..20_000) ua *= 1000 // device reports mA
            val mv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            (ua / 1_000_000f) * (mv / 1000f)
        }.getOrNull()?.takeIf { it in 0.3f..250f } else null

        return SystemSnapshot(
            batteryPct = pct,
            charging = charging,
            batteryTempC = temp,
            ramUsedPct = ramUsed,
            ramTotalGb = mi.totalMem / 1_073_741_824f,
            storageUsedPct = storagePct,
            storageFreeGb = (free / 1_073_741_824.0).toFloat(),
            network = networkLabel(),
            uptime = uptime,
            nextAlarm = alarm,
            plug = if (charging) plug ?: "AC" else null,
            timeToFullMs = ttf,
            watts = watts,
        )
    }

    private fun networkLabel(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "OFFLINE"
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return "OFFLINE"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "LINKED"
        }
    }

    fun flow(periodMs: Long = 2000): Flow<SystemSnapshot> = flow {
        while (true) {
            emit(runCatching { snapshot() }.getOrDefault(SystemSnapshot()))
            delay(periodMs)
        }
    }
}
