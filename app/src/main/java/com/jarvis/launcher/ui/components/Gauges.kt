package com.jarvis.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RingGauge(
    value: Float,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    warn: Boolean = false,
) {
    val hud = LocalHud.current
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(900), label = "gauge")
    val color = if (warn) hud.warn else hud.primary
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.08f
                val inset = stroke * 1.5f
                val tl = Offset(inset, inset)
                val sz = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                drawArc(color.copy(alpha = 0.15f), 135f, 270f, false, tl, sz, style = Stroke(stroke))
                drawArc(color, 135f, 270f * v, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Butt))
                val r = this.size.minDimension / 2f
                for (i in 0..18) {
                    val ang = Math.toRadians((135 + i * 15).toDouble())
                    val r0 = r * 0.98f
                    val r1 = r * if (i % 3 == 0) 0.88f else 0.93f
                    drawLine(
                        color.copy(alpha = if (i / 18f <= v) 0.9f else 0.25f),
                        Offset(center.x + (cos(ang) * r0).toFloat(), center.y + (sin(ang) * r0).toFloat()),
                        Offset(center.x + (cos(ang) * r1).toFloat(), center.y + (sin(ang) * r1).toFloat()),
                        1.5f,
                    )
                }
            }
            Text(valueText, color = hud.text, fontFamily = HudMono, fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = HudType.label, color = hud.textDim)
    }
}

@Composable
fun BarMeter(value: Float, modifier: Modifier = Modifier, color: Color? = null) {
    val hud = LocalHud.current
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(700), label = "bar")
    val c = color ?: hud.primary
    Canvas(modifier) {
        val segs = 20
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segs - 1)) / segs
        for (i in 0 until segs) {
            val on = i < (v * segs).toInt()
            drawRect(
                c.copy(alpha = if (on) 0.9f else 0.15f),
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
            )
        }
    }
}
