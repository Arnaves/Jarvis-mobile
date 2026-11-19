package com.jarvis.launcher.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ui.components.ArcReactor
import com.jarvis.launcher.ui.components.BarMeter
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val bootLines = listOf(
    "BIOS HANDSHAKE ............ OK",
    "LOADING KERNEL MODULES ..... OK",
    "MOUNTING SECURE ENCLAVE .... OK",
    "CALIBRATING HUD OPTICS ..... OK",
    "VOICE SUBSYSTEM ............ OK",
    "NEURAL CORE ................ LOCAL",
    "ALL SYSTEMS ONLINE",
)

@Composable
fun BootSequence(onDone: () -> Unit) {
    val hud = LocalHud.current
    val shown = remember { mutableStateListOf<String>() }
    val progress = remember { Animatable(0f) }
    val scale = remember { Animatable(0.2f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        launch { progress.animateTo(1f, tween(1900)) }
        for (l in bootLines) { delay(240); shown += l }
        delay(350)
        fade.animateTo(0f, tween(450))
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(fade.value)
            .background(Color.Black.copy(alpha = 0.92f))
            .pointerInput(Unit) { detectTapGestures { onDone() } }
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(200.dp).scale(scale.value)) { ArcReactor(CoreMode.THINKING, 0f, Modifier.fillMaxSize()) }
            Spacer(Modifier.height(28.dp))
            Text("J.A.R.V.I.S", style = HudType.title, color = hud.primary)
            Spacer(Modifier.height(16.dp))
            BarMeter(progress.value, Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().height(150.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach { Text(it, style = HudType.label, color = if (it.startsWith("ALL")) hud.secondary else hud.textDim) }
            }
        }
    }
}

@Composable
fun OnboardingCard(
    isDefault: Boolean,
    micGranted: Boolean,
    onSetDefault: () -> Unit,
    onGrantMic: () -> Unit,
    onOpenCore: () -> Unit,
    onDone: () -> Unit,
) {
    val hud = LocalHud.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).systemBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
        com.jarvis.launcher.ui.components.HudPanel(Modifier.fillMaxWidth(), title = "Initial configuration", contentPadding = 20.dp) {
            Text("Welcome. I'm JARVIS, your on-device assistant and home screen.", style = HudType.body, color = hud.text)
            Spacer(Modifier.height(16.dp))
            Step("01", "Make me your home screen", isDefault, "SET DEFAULT", onSetDefault)
            Step("02", "Allow microphone for voice commands", micGranted, "GRANT", onGrantMic)
            Step("03", "Optional: install a neural core for open conversation", false, "SETUP", onOpenCore)
            Spacer(Modifier.height(16.dp))
            Text(
                "Tap the core to talk · hold it for the console · swipe up for apps · swipe down for notifications · long-press an app to pin it.",
                style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
            )
            Spacer(Modifier.height(16.dp))
            HudButton("ENGAGE", onClick = onDone)
        }
    }
}

@Composable
private fun Step(num: String, text: String, done: Boolean, action: String, onClick: () -> Unit) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(num, style = HudType.data, color = hud.primary)
        Spacer(Modifier.size(12.dp))
        Text(text, style = HudType.body.copy(fontSize = 14.sp), color = hud.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        if (done) Text("✓ DONE", style = HudType.label, color = hud.secondary) else HudButton(action, onClick = onClick)
    }
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
