package com.jarvis.launcher.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.ai.ChatMessage
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ai.VoiceState
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.components.ArcReactor
import com.jarvis.launcher.ui.components.HudPanel
import com.jarvis.launcher.ui.components.RingGauge
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlinx.coroutines.launch

/**
 * Sci-fi lock screen. Intro: radar sweep + identity scan. Swipe up: core overload flash
 * and the system keyguard (PIN / fingerprint) takes over.
 */
@Composable
fun LockScreen(
    sys: SystemSnapshot,
    mode: CoreMode,
    voice: VoiceState,
    lastMessage: ChatMessage?,
    handsFree: Boolean,
    showSeconds: Boolean,
    unlockSignal: Int,
    surge: Boolean = false,
    introKey: Int = 0,
    onSurgeDone: () -> Unit = {},
    onMic: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val hud = LocalHud.current
    val scope = rememberCoroutineScope()
    val intro = remember { Animatable(0f) }
    val unlock = remember { Animatable(0f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var unlocking by remember { mutableStateOf(false) }
    val thresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    fun engage() {
        if (unlocking) return
        unlocking = true
        scope.launch {
            unlock.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            onUnlocked()
        }
    }

    // Replays each time the display lights up (the activity is pre-armed while the screen is off).
    LaunchedEffect(introKey) {
        intro.snapTo(0f)
        intro.animateTo(1f, tween(1400, easing = LinearEasing))
    }
    // External unlock (fingerprint / face) also plays the exit animation.
    LaunchedEffect(unlockSignal) { if (unlockSignal > 0) engage() }

    val dragFrac = (-drag / thresholdPx).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (-drag > thresholdPx) engage() else drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, dy -> if (!unlocking) drag = (drag + dy).coerceAtMost(0f) }
            },
    ) {
        // Radar sweep + scan grid during intro
        Canvas(Modifier.fillMaxSize()) {
            val p = intro.value
            if (p < 1f) {
                val y = size.height * p
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, hud.primary.copy(alpha = 0.35f), Color.Transparent), y - 120f, y + 8f),
                    topLeft = Offset(0f, y - 120f), size = androidx.compose.ui.geometry.Size(size.width, 128f),
                )
                drawLine(hud.secondary, Offset(0f, y), Offset(size.width, y), 2f)
            }
            val ringP = (p * 1.4f).coerceAtMost(1f)
            drawCircle(
                hud.primary.copy(alpha = (1f - ringP) * 0.6f), radius = size.maxDimension * ringP,
                center = Offset(size.width / 2, size.height * 0.48f), style = Stroke(3f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
                .graphicsLayer {
                    translationY = drag * 0.45f
                    alpha = (1f - dragFrac * 0.5f) * (1f - unlock.value) * intro.value.coerceAtLeast(0.15f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("J.A.R.V.I.S", style = HudType.title, color = hud.primary)
                Spacer(Modifier.size(10.dp))
                Text("· SECURE MODE", style = HudType.label, color = hud.textDim)
            }
            Spacer(Modifier.height(14.dp))
            HudClock(showSeconds, sys, scale = 1.25f)

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .size(250.dp)
                    .graphicsLayer {
                        val s = 0.7f + 0.3f * intro.value + dragFrac * 0.15f + unlock.value * 2.2f
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (sys.charging) {
                    com.jarvis.launcher.ui.components.EnergyStream(sys.batteryPct / 100f, Modifier.fillMaxSize())
                    ArcReactor(if (unlocking) CoreMode.THINKING else mode, voice.level, Modifier.size(175.dp))
                } else {
                    ArcReactor(if (unlocking) CoreMode.THINKING else mode, voice.level, Modifier.fillMaxSize())
                }
            }
            if (sys.charging) {
                Spacer(Modifier.height(4.dp))
                com.jarvis.launcher.ui.components.ChargingReadout(sys)
            }

            Spacer(Modifier.height(10.dp))
            val caption = when {
                voice.partial.isNotBlank() -> "› " + voice.partial
                mode == CoreMode.LISTENING -> "LISTENING…"
                mode == CoreMode.THINKING -> "PROCESSING…"
                intro.value < 1f -> "IDENTITY SCAN ${(intro.value * 100).toInt()}%"
                handsFree -> "SAY “HEY JARVIS”"
                else -> "TAP MIC TO SPEAK"
            }
            Text(caption.uppercase(), style = HudType.label, color = hud.textDim, textAlign = TextAlign.Center)

            lastMessage?.let { m ->
                Spacer(Modifier.height(12.dp))
                HudPanel(Modifier.fillMaxWidth(), title = "JARVIS") {
                    Text(m.text.ifBlank { "…" }, style = HudType.body, color = hud.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            Spacer(Modifier.weight(1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RingGauge(sys.batteryPct / 100f, if (sys.charging) "CHARGING" else "POWER", "${sys.batteryPct}", size = 54.dp, warn = sys.batteryPct < 20 && !sys.charging)
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(hud.primary.copy(alpha = if (mode == CoreMode.LISTENING) 0.3f else 0.12f))
                        .border(1.5.dp, hud.primary, CircleShape)
                        .clickable(onClick = onMic),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Mic, "Speak", tint = hud.primary, modifier = Modifier.size(28.dp)) }
                RingGauge(sys.ramUsedPct / 100f, "MEMORY", "${sys.ramUsedPct}", size = 54.dp)
            }

            Spacer(Modifier.height(18.dp))
            SwipeChevrons(dragFrac)
            Spacer(Modifier.height(14.dp))
        }

        if (surge) com.jarvis.launcher.ui.components.PowerSurge(sys, onDone = onSurgeDone)

        // Overload flash on unlock
        if (unlock.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha((if (unlock.value < 0.4f) unlock.value / 0.4f else 1f - (unlock.value - 0.4f) / 0.6f) * 0.85f)
                    .background(Brush.radialGradient(listOf(Color.White, hud.primary, Color.Transparent))),
            )
        }
    }
}

@Composable
private fun SwipeChevrons(progress: Float) {
    val hud = LocalHud.current
    val t by rememberInfiniteTransition(label = "chev").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (i in 2 downTo 0) {
            val phase = ((t * 3f - i) % 3f + 3f) % 3f
            val a = (if (phase < 1f) phase else if (phase < 2f) 2f - phase else 0f).coerceIn(0.15f, 1f)
            Icon(
                Icons.Rounded.KeyboardArrowUp, null,
                tint = hud.primary.copy(alpha = (a + progress).coerceAtMost(1f)),
                modifier = Modifier.size(26.dp).padding(0.dp),
            )
        }
        Text("SWIPE UP TO ENGAGE", style = HudType.label.copy(fontSize = 10.sp, letterSpacing = 3.sp), color = hud.textDim)
    }
}
