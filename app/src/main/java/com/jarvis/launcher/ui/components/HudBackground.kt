package com.jarvis.launcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jarvis.launcher.ui.theme.LocalHud
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Seconds since first composition, advanced every frame. */
@Composable
fun rememberHudTime(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1_000_000_000f }
    }
    return t
}

private data class Particle(val x: Float, val speed: Float, val phase: Float, val size: Float)

@Composable
fun HudBackground(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val hud = LocalHud.current
    val particles = remember { List(36) { Particle(Random.nextFloat(), 0.01f + Random.nextFloat() * 0.03f, Random.nextFloat(), 1f + Random.nextFloat() * 2f) } }
    val timeState = rememberHudTime()

    Canvas(
        modifier
            .fillMaxSize()
            .drawWithCache {
                // Static layers: gradient, hex grid and vignette are cached.
                val hex = Path()
                val r = size.minDimension / 22f
                val w = sqrt(3f) * r
                val rows = (size.height / (1.5f * r)).toInt() + 2
                val cols = (size.width / w).toInt() + 2
                for (row in 0..rows) for (col in 0..cols) {
                    val cx = col * w + if (row % 2 == 1) w / 2 else 0f
                    val cy = row * 1.5f * r
                    for (i in 0..6) {
                        val a = Math.toRadians((60 * i - 30).toDouble())
                        val px = cx + r * cos(a).toFloat()
                        val py = cy + r * sin(a).toFloat()
                        if (i == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
                    }
                }
                val bg = Brush.verticalGradient(listOf(hud.bgTop, hud.bgBottom, hud.bgTop))
                val glow = Brush.radialGradient(
                    listOf(hud.primary.copy(alpha = 0.16f * intensity), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.52f),
                    radius = size.maxDimension * 0.55f,
                )
                val vignette = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = size.maxDimension * 0.75f,
                )
                onDrawBehind {
                    drawRect(bg)
                    drawRect(glow)
                    drawPath(hex, hud.primary.copy(alpha = 0.045f), style = Stroke(1f))
                    drawRect(vignette)
                }
            }
    ) {
        val time = timeState.value
        // Animated scan line
        val scanY = (time * 0.12f % 1.2f - 0.1f) * size.height
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, hud.primary.copy(alpha = 0.07f * intensity), Color.Transparent),
                startY = scanY - 60f, endY = scanY + 60f,
            ),
            topLeft = Offset(0f, scanY - 60f),
            size = androidx.compose.ui.geometry.Size(size.width, 120f),
        )
        // Drifting data motes
        particles.forEach { p ->
            val y = (1f - ((p.phase + time * p.speed) % 1f)) * size.height
            val x = p.x * size.width + sin(time * 0.6f + p.phase * 10f) * 12f
            val a = (0.15f + 0.35f * ((sin(time * 2f + p.phase * 20f) + 1f) / 2f)) * intensity
            drawCircle(hud.secondary.copy(alpha = a), radius = p.size, center = Offset(x, y))
        }
        // Horizontal interference lines
        for (i in 0 until 3) {
            val y = size.height * ((i * 0.37f + time * 0.03f) % 1f)
            drawLine(hud.primary.copy(alpha = 0.03f), Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}
