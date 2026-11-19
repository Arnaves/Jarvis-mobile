package com.jarvis.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ui.theme.LocalHud
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The animated reactor core: concentric rotating rings that react to the assistant state.
 * Idle: slow breathing. Listening: audio-reactive spectrum. Thinking: fast orbitals. Speaking: ripples.
 */
@Composable
fun ArcReactor(mode: CoreMode, level: Float, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    val currentMode by rememberUpdatedState(mode)
    var t by remember { mutableFloatStateOf(0f) }
    var a1 by remember { mutableFloatStateOf(0f) }
    var a2 by remember { mutableFloatStateOf(0f) }
    var a3 by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val speed = when (currentMode) {
                CoreMode.IDLE -> 1f
                CoreMode.LISTENING -> 2.2f
                CoreMode.THINKING -> 6f
                CoreMode.SPEAKING -> 2.8f
            }
            t += dt
            a1 += dt * 6f * speed
            a2 -= dt * 14f * speed
            a3 += dt * 30f * speed
        }
    }
    val lvl by animateFloatAsState(if (mode == CoreMode.LISTENING) level else 0f, tween(90), label = "lvl")
    val energy by animateFloatAsState(
        when (mode) { CoreMode.IDLE -> 0.55f; CoreMode.LISTENING -> 0.85f; CoreMode.THINKING -> 1f; CoreMode.SPEAKING -> 0.9f },
        tween(500), label = "energy",
    )

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * 0.92f
        val pulse = (sin(t * 2.2f) + 1f) / 2f
        val primary = hud.primary
        val secondary = hud.secondary

        // Halo
        drawCircle(
            Brush.radialGradient(
                listOf(primary.copy(alpha = 0.35f * energy + lvl * 0.3f), primary.copy(alpha = 0.06f), Color.Transparent),
                center = c, radius = r * (1.15f + lvl * 0.15f),
            ),
            radius = r * (1.15f + lvl * 0.15f), center = c,
        )

        // Outer tick ring
        rotate(a1, c) {
            for (i in 0 until 120) {
                val ang = i * 3f * (PI / 180f).toFloat()
                val long = i % 10 == 0
                val mid = i % 5 == 0
                val inner = r * if (long) 0.90f else if (mid) 0.93f else 0.95f
                drawLine(
                    primary.copy(alpha = if (long) 0.9f else 0.45f),
                    Offset(c.x + cos(ang) * inner, c.y + sin(ang) * inner),
                    Offset(c.x + cos(ang) * r, c.y + sin(ang) * r),
                    strokeWidth = if (long) 3f else 1.5f,
                )
            }
        }
        drawCircle(primary.copy(alpha = 0.25f), radius = r * 0.885f, center = c, style = Stroke(1.2f))

        // Segmented arc ring
        rotate(a2, c) {
            val rr = r * 0.80f
            val tl = Offset(c.x - rr, c.y - rr)
            val sz = Size(rr * 2, rr * 2)
            for (k in 0 until 3) {
                drawArc(primary.copy(alpha = 0.85f), k * 120f, 78f, false, tl, sz, style = Stroke(r * 0.045f, cap = StrokeCap.Butt))
                drawArc(secondary.copy(alpha = 0.5f), k * 120f + 84f, 18f, false, tl, sz, style = Stroke(r * 0.045f))
            }
        }

        // Dashed ring
        rotate(-a1 * 1.5f, c) {
            drawCircle(
                primary.copy(alpha = 0.5f), radius = r * 0.70f, center = c,
                style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.03f, r * 0.05f))),
            )
        }

        // Spectrum when listening / speaking
        if (lvl > 0.01f || mode == CoreMode.SPEAKING) {
            val bars = 72
            for (i in 0 until bars) {
                val ang = (i.toFloat() / bars) * 2f * PI.toFloat()
                val n = (sin(i * 1.7f + t * 9f) + sin(i * 0.6f - t * 5f) + 2f) / 4f
                val amp = if (mode == CoreMode.SPEAKING) 0.06f + 0.06f * n * pulse else 0.02f + lvl * 0.16f * n
                val r0 = r * 0.62f
                val r1 = r0 + r * amp
                drawLine(
                    secondary.copy(alpha = 0.85f),
                    Offset(c.x + cos(ang) * r0, c.y + sin(ang) * r0),
                    Offset(c.x + cos(ang) * r1, c.y + sin(ang) * r1),
                    strokeWidth = 3f, cap = StrokeCap.Round,
                )
            }
        }

        // Coil segments
        rotate(a2 * 0.5f, c) {
            val rr = r * 0.52f
            val tl = Offset(c.x - rr, c.y - rr)
            val sz = Size(rr * 2, rr * 2)
            for (k in 0 until 10) {
                drawArc(
                    primary.copy(alpha = 0.55f + 0.35f * energy), k * 36f + 3f, 28f, false, tl, sz,
                    style = Stroke(r * 0.12f),
                )
            }
        }
        drawCircle(primary.copy(alpha = 0.8f), radius = r * 0.40f, center = c, style = Stroke(2.5f))

        // Thinking orbitals
        if (mode == CoreMode.THINKING) {
            for (k in 0 until 3) {
                val ang = (a3 * (1f + k * 0.35f) + k * 120f) * (PI / 180f).toFloat()
                val rr = r * (0.97f - k * 0.08f)
                drawCircle(secondary, radius = r * 0.025f, center = Offset(c.x + cos(ang) * rr, c.y + sin(ang) * rr))
            }
        }

        // Speaking ripples
        if (mode == CoreMode.SPEAKING) {
            for (k in 0 until 3) {
                val p = (t * 0.8f + k / 3f) % 1f
                drawCircle(primary.copy(alpha = (1f - p) * 0.5f), radius = r * (0.4f + p * 0.7f), center = c, style = Stroke(2f))
            }
        }

        // Core
        val coreR = r * (0.30f + 0.02f * pulse + lvl * 0.06f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, secondary.copy(alpha = 0.95f), primary.copy(alpha = 0.6f), Color.Transparent),
                center = c, radius = coreR * 1.25f,
            ),
            radius = coreR * 1.25f, center = c,
        )
        drawCore(c, coreR, primary, t)
    }
}

private fun DrawScope.drawCore(c: Offset, r: Float, color: Color, t: Float) {
    // Hexagonal inner lattice
    val path = androidx.compose.ui.graphics.Path()
    for (i in 0..6) {
        val a = (60f * i + t * 20f) * (PI / 180f).toFloat()
        val p = Offset(c.x + cos(a) * r * 0.55f, c.y + sin(a) * r * 0.55f)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color.copy(alpha = 0.7f), style = Stroke(2f))
    drawCircle(Color.White.copy(alpha = 0.9f), radius = r * 0.22f, center = c)
}
