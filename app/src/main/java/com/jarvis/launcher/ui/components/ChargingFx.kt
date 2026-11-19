package com.jarvis.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.system.SystemSnapshot
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Energy flowing into the core: particle streams rising from the bottom edge,
 * flickering arcs of lightning and a liquid fill ring showing the charge level.
 * Draw it behind / around the reactor.
 */
@Composable
fun EnergyStream(level: Float, modifier: Modifier = Modifier, coreCenterFraction: Float = 0.5f, intensity: Float = 1f) {
    val hud = LocalHud.current
    val time = rememberHudTime()
    val seeds = remember { List(42) { Triple(Random.nextFloat(), Random.nextFloat(), 0.4f + Random.nextFloat()) } }
    Canvas(modifier) {
        val t = time.value
        val core = Offset(size.width / 2, size.height * coreCenterFraction)
        // Particles: travel from the bottom edge to the core along a gentle S-curve.
        seeds.forEach { (x0, phase, speed) ->
            val p = ((t * 0.35f * speed + phase) % 1f)
            val start = Offset(size.width * (0.15f + x0 * 0.7f), size.height + 10f)
            val ease = p * p * (3 - 2 * p)
            val x = start.x + (core.x - start.x) * ease + sin((p * 6f + x0 * 10f) * PI.toFloat()) * 18f * (1 - p)
            val y = start.y + (core.y - start.y) * ease
            val a = (if (p < 0.15f) p / 0.15f else 1f) * (1f - p * 0.6f) * intensity
            drawCircle(hud.secondary.copy(alpha = 0.8f * a), radius = 2.2f + 2.5f * (1 - p), center = Offset(x, y))
            drawCircle(hud.primary.copy(alpha = 0.18f * a), radius = 7f * (1 - p) + 2f, center = Offset(x, y))
        }
        // Lightning arcs: re-randomised ~8 times per second.
        val tick = (t * 8f).toInt()
        repeat(2) { k ->
            val rnd = Random(tick * 31 + k * 7)
            if (rnd.nextFloat() > 0.55f * intensity) return@repeat
            val path = Path()
            var px = size.width * (0.3f + rnd.nextFloat() * 0.4f)
            var py = size.height
            path.moveTo(px, py)
            val steps = 9
            for (i in 1..steps) {
                val f = i / steps.toFloat()
                px += (core.x - px) * (1f / (steps - i + 1)) + (rnd.nextFloat() - 0.5f) * 46f * (1 - f)
                py = size.height + (core.y - size.height) * f
                path.lineTo(px, py)
            }
            drawPath(path, hud.secondary.copy(alpha = 0.75f), style = Stroke(2.2f, cap = StrokeCap.Round))
            drawPath(path, hud.primary.copy(alpha = 0.25f), style = Stroke(8f, cap = StrokeCap.Round))
        }
        // Charge ring around the core
        val r = size.minDimension * 0.36f
        val tl = Offset(core.x - r, core.y - r)
        drawArc(hud.primary.copy(alpha = 0.15f), -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(10f))
        drawArc(
            Brush.sweepGradient(listOf(hud.primary, hud.secondary, hud.primary), center = core),
            -90f, 360f * level.coerceIn(0f, 1f), false, tl, Size(r * 2, r * 2), style = Stroke(10f, cap = StrokeCap.Round),
        )
        // Running pulse along the ring
        val ang = (-90f + 360f * level * ((t * 0.5f) % 1f)) * (PI / 180f).toFloat()
        drawCircle(Color.White, radius = 6f, center = Offset(core.x + cos(ang) * r, core.y + sin(ang) * r))
    }
}

/** Telemetry block shown while charging. */
@Composable
fun ChargingReadout(sys: SystemSnapshot, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${sys.batteryPct}%", color = hud.text, fontSize = 44.sp, fontWeight = FontWeight.Thin, fontFamily = HudMono)
        Text(
            listOfNotNull(
                when (sys.plug) { "WIRELESS" -> "WIRELESS CHARGING"; "USB" -> "USB CHARGING"; else -> if (sys.batteryPct >= 100) "FULLY CHARGED" else "CHARGING" },
                sys.watts?.let { "%.1f W".format(it) },
            ).joinToString(" · "),
            style = HudType.label, color = hud.secondary,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            sys.timeToFullMs?.let { Stat("FULL IN", formatEta(it)) }
            Stat("CELL", "%.1f°C".format(sys.batteryTempC))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val hud = LocalHud.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = HudType.label.copy(fontSize = 8.sp), color = hud.textDim)
        Text(value, style = HudType.data, color = hud.primary)
    }
}

fun formatEta(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

/**
 * Full-screen "power surge" played when a charger is connected: shockwave rings,
 * the core igniting and the charge level counting up.
 */
@Composable
fun PowerSurge(sys: SystemSnapshot, onDone: () -> Unit, modifier: Modifier = Modifier, holdMs: Long = 3200, reactorSize: Dp = 230.dp) {
    val hud = LocalHud.current
    val p = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(holdMs - 1300)
        fade.animateTo(0f, tween(500))
        onDone()
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val a = fade.value
            drawRect(Color.Black.copy(alpha = 0.82f * a))
            val c = Offset(size.width / 2, size.height * 0.45f)
            for (k in 0 until 3) {
                val q = ((p.value * 1.3f) - k * 0.18f).coerceIn(0f, 1f)
                if (q > 0f) drawCircle(
                    hud.primary.copy(alpha = (1f - q) * 0.8f * a), radius = size.maxDimension * 0.7f * q, center = c, style = Stroke(4f + 10f * (1 - q)),
                )
            }
            if (p.value < 0.25f) drawRect(Color.White.copy(alpha = (0.25f - p.value) * 2f * a))
        }
        if (fade.value > 0.02f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(reactorSize), contentAlignment = Alignment.Center) {
                    EnergyStream(sys.batteryPct / 100f * p.value, Modifier.fillMaxSize(), intensity = fade.value)
                    ArcReactor(CoreMode.THINKING, 0f, Modifier.size(reactorSize * 0.72f))
                }
                Spacer(Modifier.height(18.dp))
                Text("POWER SURGE DETECTED", style = HudType.title, color = hud.primary.copy(alpha = fade.value))
                Spacer(Modifier.height(8.dp))
                ChargingReadout(sys.copy(batteryPct = (sys.batteryPct * p.value).toInt()), Modifier)
            }
        }
    }
}
