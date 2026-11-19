package com.jarvis.launcher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.data.AppEntry
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.LocalHud

@Composable
fun AppIcon(entry: AppEntry, repo: AppRepository, size: Dp, modifier: Modifier = Modifier, framed: Boolean = true) {
    val hud = LocalHud.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, entry.key, px) { value = repo.icon(entry, px) }
    Box(
        modifier
            .size(size)
            .then(
                if (framed) Modifier
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(hud.primary.copy(alpha = 0.18f), Color.Transparent)))
                    .border(1.dp, hud.primary.copy(alpha = 0.55f), CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        val inner = if (framed) size * 0.68f else size
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = entry.label, modifier = Modifier.size(inner))
        } else {
            Text(
                entry.label.take(1).uppercase(), color = hud.primary, fontFamily = HudMono,
                fontWeight = FontWeight.Bold, fontSize = (size.value * 0.35f).sp, modifier = Modifier.padding(2.dp),
            )
        }
    }
}
