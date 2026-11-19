package com.jarvis.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import com.jarvis.launcher.ui.theme.HudPalette

/** Cut-corner holographic frame with bright corner brackets. */
fun Modifier.hudFrame(hud: HudPalette, cut: Dp = 12.dp, fillAlpha: Float = 0.06f, accent: Color = hud.primary): Modifier = drawBehind {
    val c = cut.toPx()
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(c, 0f); lineTo(w, 0f); lineTo(w, h - c); lineTo(w - c, h); lineTo(0f, h); lineTo(0f, c); close()
    }
    drawPath(path, Brush.verticalGradient(listOf(accent.copy(alpha = fillAlpha * 1.6f), accent.copy(alpha = fillAlpha * 0.4f))))
    drawPath(path, accent.copy(alpha = 0.35f), style = Stroke(1.2f))
    val b = c * 1.6f
    val sw = 3f
    // brackets
    drawLine(accent, Offset(c, 0f), Offset(c + b, 0f), sw)
    drawLine(accent, Offset(0f, c), Offset(0f, c + b * 0.6f), sw)
    drawLine(accent, Offset(w, h - c), Offset(w, h - c - b * 0.6f), sw)
    drawLine(accent, Offset(w - c, h), Offset(w - c - b, h), sw)
    drawLine(accent.copy(alpha = 0.9f), Offset(0f, c), Offset(c, 0f), sw)
    drawLine(accent.copy(alpha = 0.9f), Offset(w, h - c), Offset(w - c, h), sw)
}

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    contentPadding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hud = LocalHud.current
    val a = accent ?: hud.primary
    Box(modifier.hudFrame(hud, accent = a)) {
        Column(Modifier.padding(contentPadding)) {
            if (title != null) {
                Text(title.uppercase(), style = HudType.label, color = a)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}
