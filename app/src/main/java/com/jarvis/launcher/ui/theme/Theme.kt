package com.jarvis.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class HudPalette(
    val id: String,
    val title: String,
    val primary: Color,
    val secondary: Color,
    val warn: Color,
    val bgTop: Color,
    val bgBottom: Color,
) {
    val dim: Color get() = primary.copy(alpha = 0.35f)
    val faint: Color get() = primary.copy(alpha = 0.12f)
    val text: Color get() = Color(0xFFE6FBFF)
    val textDim: Color get() = Color(0xFF8FB6C2)
}

object HudPalettes {
    val ArcCyan = HudPalette(
        "arc", "Arc Reactor", Color(0xFF2EE6FF), Color(0xFF7CF5FF), Color(0xFFFFB238),
        Color(0xFF020A12), Color(0xFF00141F),
    )
    val MarkRed = HudPalette(
        "mark", "Hot Rod", Color(0xFFFF4D3D), Color(0xFFFFC94A), Color(0xFFFFE07A),
        Color(0xFF0E0303), Color(0xFF1C0705),
    )
    val Gold = HudPalette(
        "gold", "Titanium Gold", Color(0xFFFFC94A), Color(0xFFFFE9A8), Color(0xFFFF6B4A),
        Color(0xFF0B0802), Color(0xFF171004),
    )
    val Stealth = HudPalette(
        "stealth", "Stealth", Color(0xFFDDE6EE), Color(0xFFFFFFFF), Color(0xFFFF5A5A),
        Color(0xFF040506), Color(0xFF0C0F12),
    )
    val Emerald = HudPalette(
        "emerald", "Gamma", Color(0xFF3DFF9A), Color(0xFFB4FFD6), Color(0xFFFFD23D),
        Color(0xFF010B06), Color(0xFF03170D),
    )
    val Violet = HudPalette(
        "violet", "Vision", Color(0xFFB57BFF), Color(0xFFFFD36E), Color(0xFFFF6E9C),
        Color(0xFF07030F), Color(0xFF12071F),
    )
    val all = listOf(ArcCyan, MarkRed, Gold, Stealth, Emerald, Violet)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: ArcCyan
}

val LocalHud = staticCompositionLocalOf { HudPalettes.ArcCyan }

val HudMono = FontFamily.Monospace

object HudType {
    val label = TextStyle(fontFamily = HudMono, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp)
    val data = TextStyle(fontFamily = HudMono, fontSize = 13.sp, letterSpacing = 1.sp)
    val title = TextStyle(fontFamily = HudMono, fontSize = 14.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun JarvisTheme(palette: HudPalette, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = palette.primary,
        secondary = palette.secondary,
        background = palette.bgTop,
        surface = palette.bgBottom,
        onPrimary = Color.Black,
        onSurface = palette.text,
        onBackground = palette.text,
    )
    CompositionLocalProvider(LocalHud provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
