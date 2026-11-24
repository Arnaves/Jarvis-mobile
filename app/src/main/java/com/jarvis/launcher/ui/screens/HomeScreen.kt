package com.jarvis.launcher.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.ai.ChatMessage
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ai.CoreStatus
import com.jarvis.launcher.ai.LlmState
import com.jarvis.launcher.ai.VoiceState
import com.jarvis.launcher.data.AppEntry
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.components.AppIcon
import com.jarvis.launcher.ui.components.ArcReactor
import com.jarvis.launcher.ui.components.HudPanel
import com.jarvis.launcher.ui.components.RingGauge
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repo: AppRepository,
    dock: List<AppEntry>,
    sys: SystemSnapshot,
    mode: CoreMode,
    voice: VoiceState,
    llm: LlmState,
    lastMessage: ChatMessage?,
    showSeconds: Boolean,
    handsFree: Boolean = false,
    cloudOnline: Boolean = false,
    wakeWord: String = "jarvis",
    onCoreTap: () -> Unit,
    onCoreHold: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
    onPullDown: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onDockLongPress: (AppEntry) -> Unit,
) {
    val hud = LocalHud.current
    val haptics = LocalHapticFeedback.current

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total < -120f) onOpenDrawer() else if (total > 160f) onPullDown()
                        total = 0f
                    },
                ) { _, dy -> total += dy }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onCoreTap() }) }
            .systemBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TopStatus(sys, llm, cloudOnline, onOpenSettings)
            Spacer(Modifier.height(6.dp))
            HudClock(showSeconds, sys)

            Spacer(Modifier.weight(1f))

            // Reactor + side telemetry
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideTelemetry(
                    listOf("PWR" to "${sys.batteryPct}%", "TMP" to "%.0f°".format(sys.batteryTempC), "NET" to sys.network.take(4)),
                    Alignment.End,
                )
                Box(
                    Modifier
                        .size(230.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onCoreTap() },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onCoreHold() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sys.charging) {
                        com.jarvis.launcher.ui.components.EnergyStream(sys.batteryPct / 100f, Modifier.fillMaxSize(), intensity = 0.7f)
                        com.jarvis.launcher.ui.components.JarvisCore(mode, voice.level, Modifier.size(170.dp))
                    } else {
                        com.jarvis.launcher.ui.components.JarvisCore(mode, voice.level, Modifier.fillMaxSize())
                    }
                }
                SideTelemetry(
                    listOf("RAM" to "${sys.ramUsedPct}%", "DSK" to "${sys.storageUsedPct}%", "UP" to sys.uptime.substringBefore(' ')),
                    Alignment.Start,
                )
            }
            Spacer(Modifier.height(8.dp))
            CoreCaption(mode, voice, handsFree, wakeWord)

            Spacer(Modifier.height(14.dp))
            ResponseTicker(lastMessage, voice, onOpenConsole)

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingGauge(sys.batteryPct / 100f, "POWER", "${sys.batteryPct}", size = 58.dp, warn = sys.batteryPct < 20 && !sys.charging)
                RingGauge(sys.ramUsedPct / 100f, "MEMORY", "${sys.ramUsedPct}", size = 58.dp, warn = sys.ramUsedPct > 90)
                RingGauge(sys.storageUsedPct / 100f, "STORAGE", "${sys.storageUsedPct}", size = 58.dp, warn = sys.storageUsedPct > 90)
            }
            Spacer(Modifier.height(18.dp))

            Dock(repo, dock, onLaunch, onDockLongPress)
            SwipeHint(onOpenDrawer)
        }
    }
}

@Composable
private fun TopStatus(sys: SystemSnapshot, llm: LlmState, cloud: Boolean, onOpenSettings: () -> Unit) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("J.A.R.V.I.S", style = HudType.title, color = hud.primary)
            val (label, color) = if (cloud) "GEMINI UPLINK · ONLINE" to hud.secondary else when (llm.status) {
                CoreStatus.READY -> "NEURAL CORE · ONLINE" to hud.secondary
                CoreStatus.LOADING -> "NEURAL CORE · BOOTING" to hud.warn
                CoreStatus.ERROR -> "NEURAL CORE · FAULT" to hud.warn
                CoreStatus.NOT_INSTALLED -> "COMMAND MODE · LOCAL" to hud.textDim
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Blink(color)
                Spacer(Modifier.width(6.dp))
                Text(label, style = HudType.label, color = color)
            }
        }
        Text(
            "${sys.network} · ${sys.batteryPct}%${if (sys.charging) " ⚡" else ""}",
            style = HudType.label, color = hud.textDim,
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = hud.primary)
        }
    }
}

@Composable
private fun Blink(color: androidx.compose.ui.graphics.Color) {
    val a by rememberInfiniteTransition(label = "blink").animateFloat(
        0.2f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.size(6.dp).alpha(a).padding(0.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(color) }
    }
}

@Composable
fun HudClock(showSeconds: Boolean, sys: SystemSnapshot, scale: Float = 1f) {
    val hud = LocalHud.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val is24 = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    val hm = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    val sec = SimpleDateFormat("ss", Locale.getDefault()).format(Date(now))
    val date = SimpleDateFormat("EEEE · d MMM yyyy", Locale.getDefault()).format(Date(now)).uppercase()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(hm, color = hud.text, fontSize = (68 * scale).sp, fontWeight = FontWeight.Thin, fontFamily = FontFamily.SansSerif, letterSpacing = 2.sp)
            if (showSeconds) {
                Text(sec, color = hud.primary, fontFamily = HudMono, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp))
            }
            if (!is24) {
                Text(
                    SimpleDateFormat("a", Locale.getDefault()).format(Date(now)).uppercase(),
                    color = hud.textDim, style = HudType.label, modifier = Modifier.padding(start = 6.dp, bottom = 16.dp),
                )
            }
        }
        Text(date, style = HudType.label.copy(letterSpacing = 4.sp), color = hud.primary)
        sys.nextAlarm?.let {
            Spacer(Modifier.height(2.dp))
            Text("NEXT ALARM · ${it.uppercase()}", style = HudType.label, color = hud.textDim)
        }
    }
}

@Composable
private fun SideTelemetry(items: List<Pair<String, String>>, align: Alignment.Horizontal) {
    val hud = LocalHud.current
    Column(Modifier.width(52.dp), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { (k, v) ->
            Column(horizontalAlignment = align) {
                Text(k, style = HudType.label.copy(fontSize = 8.sp), color = hud.textDim)
                Text(v, style = HudType.data.copy(fontSize = 12.sp), color = hud.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CoreCaption(mode: CoreMode, voice: VoiceState, handsFree: Boolean, wakeWord: String) {
    val hud = LocalHud.current
    val text = when {
        voice.error != null -> voice.error.uppercase()
        mode == CoreMode.LISTENING -> "LISTENING…"
        mode == CoreMode.THINKING -> "PROCESSING…"
        mode == CoreMode.SPEAKING -> "TRANSMITTING"
        handsFree -> "SAY “${com.jarvis.launcher.data.Prefs.wakeDisplay(wakeWord)}” · OR TAP THE CORE"
        else -> "TAP CORE TO SPEAK · HOLD FOR CONSOLE"
    }
    AnimatedContent(text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) }, label = "cap") {
        Text(
            it, style = HudType.label, color = if (voice.error != null) hud.warn else hud.textDim,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

@Composable
private fun ResponseTicker(last: ChatMessage?, voice: VoiceState, onOpen: () -> Unit) {
    val hud = LocalHud.current
    val shown = when {
        voice.partial.isNotBlank() -> "› " + voice.partial
        last != null -> last.text.ifBlank { "…" }
        else -> "Awaiting instructions."
    }
    HudPanel(
        Modifier
            .fillMaxWidth()
            .combinedClickableCompat(onOpen),
        title = if (voice.partial.isNotBlank()) "VOICE INPUT" else "JARVIS",
    ) {
        Text(
            shown, style = HudType.body, color = hud.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(repo: AppRepository, dock: List<AppEntry>, onLaunch: (AppEntry) -> Unit, onLong: (AppEntry) -> Unit) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        dock.forEach { app ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onLaunch(app) },
                    onLongClick = { onLong(app) },
                ),
            ) {
                AppIcon(app, repo, 54.dp)
                Spacer(Modifier.height(4.dp))
                Text(
                    app.label.uppercase(), style = HudType.label.copy(fontSize = 8.sp, letterSpacing = 1.sp),
                    color = hud.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 62.dp),
                )
            }
        }
    }
}

@Composable
private fun SwipeHint(onClick: () -> Unit) {
    val hud = LocalHud.current
    val off by rememberInfiniteTransition(label = "hint").animateFloat(
        0f, -6f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "o",
    )
    Column(
        Modifier
            .padding(top = 6.dp, bottom = 6.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.KeyboardArrowUp, null, tint = hud.primary, modifier = Modifier.padding(top = (6 + off).dp.coerceAtLeast(0.dp)).size(22.dp))
        Text("APPS", style = HudType.label.copy(fontSize = 9.sp), color = hud.textDim)
    }
}
