package com.jarvis.launcher.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ui.theme.LocalHud
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Which core is drawn: "reactor" (arc reactor rings) or "orb" (particle sphere). */
val LocalCoreStyle = staticCompositionLocalOf { "reactor" }

/** The JARVIS core in the user's chosen style. */
@Composable
fun JarvisCore(mode: CoreMode, level: Float, modifier: Modifier = Modifier) {
    if (LocalCoreStyle.current == "orb") ParticleOrb(mode, level, modifier) else ArcReactor(mode, level, modifier)
}

private class Dot(val x: Float, val y: Float, val z: Float, val seed: Float)

/**
 * A rotating 3D sphere of glowing dots (Fibonacci lattice) with a vertical energy beam and a
 * soft reflection below. Listening ripples with your voice, thinking spins fast in amber,
 * speaking breathes in the secondary colour.
 */
@Composable
fun ParticleOrb(mode: CoreMode, level: Float, modifier: Modifier = Modifier) {
    val hud = LocalHud.current
    val dots = remember {
        val n = 360
        val golden = PI * (3 - sqrt(5.0))
        List(n) { i ->
            val y = 1 - (i / (n - 1f)) * 2
            val r = sqrt(1 - y * y)
            val th = golden * i
            Dot((cos(th) * r).toFloat(), y, (sin(th) * r).toFloat(), ((i * 7919) % 1000) / 1000f)
        }
    }
    val currentMode by rememberUpdatedState(mode)
    var t by remember { mutableFloatStateOf(0f) }
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f); last = now
            t += dt
            angle += dt * when (currentMode) { CoreMode.THINKING -> 2.4f; CoreMode.SPEAKING -> 0.9f; CoreMode.LISTENING -> 0.7f; else -> 0.35f }
        }
    }
    val lvl by animateFloatAsState(if (mode == CoreMode.LISTENING) level else 0f, tween(90), label = "lvl")
    val tint by animateColorAsState(
        when (mode) { CoreMode.THINKING -> hud.warn; CoreMode.SPEAKING -> hud.secondary; else -> hud.primary }, tween(450), label = "tint",
    )

    Canvas(modifier) {
        val c = center
        val R = size.minDimension * 0.36f
        val breathe = if (mode == CoreMode.SPEAKING) 1f + 0.06f * sin(t * 7f) else 1f + 0.015f * sin(t * 1.6f)

        // Energy beam: a thin vertical column of light through the core.
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.55f), Color.White.copy(alpha = 0.8f), tint.copy(alpha = 0.55f), Color.Transparent)),
            topLeft = Offset(c.x - 1.5f, 0f), size = Size(3f, size.height),
        )
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.12f), Color.Transparent)),
            topLeft = Offset(c.x - R * 0.25f, 0f), size = Size(R * 0.5f, size.height),
        )
        // Inner glow.
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.35f + lvl * 0.3f), Color.Transparent), center = c, radius = R * 1.25f), R * 1.25f, c)

        // Dots, back to front.
        val ca = cos(angle); val sa = sin(angle)
        val tilt = 0.35f; val ct = cos(tilt); val st = sin(tilt)
        val projected = dots.map { d ->
            // Rotate around Y, then tilt around X.
            var x = d.x * ca + d.z * sa
            var z = -d.x * sa + d.z * ca
            val y = d.y * ct - z * st
            z = d.y * st + z * ct
            val ripple = 1f + lvl * 0.18f * sin(t * 12f + d.seed * 20f) + (if (mode == CoreMode.THINKING) 0.04f * sin(t * 6f + d.y * 8f) else 0f)
            x *= ripple
            Triple(Offset(c.x + x * R * breathe, c.y + y * R * breathe * ripple), z, d.seed)
        }.sortedBy { it.second }
        projected.forEach { (p, z, seed) ->
            val depth = (z + 1f) / 2f // 0 back .. 1 front
            val twinkle = 0.75f + 0.25f * sin(t * 3f + seed * 30f)
            val col = lerp(tint, Color.White, depth * 0.45f)
            drawCircle(col.copy(alpha = (0.18f + 0.82f * depth) * twinkle), radius = 1.2f + 2.6f * depth, center = p)
        }

        // Reflection: a soft glowing pool below the orb.
        val poolY = c.y + R * 1.45f
        if (poolY < size.height) {
            drawOval(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), center = Offset(c.x, poolY), radius = R * 0.9f),
                topLeft = Offset(c.x - R * 0.9f, poolY - R * 0.12f), size = Size(R * 1.8f, R * 0.24f),
            )
        }
    }
}
