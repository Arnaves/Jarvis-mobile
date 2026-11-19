package com.jarvis.launcher.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.ai.ChatMessage
import com.jarvis.launcher.ai.CoreMode
import com.jarvis.launcher.ai.CoreStatus
import com.jarvis.launcher.ai.LlmState
import com.jarvis.launcher.ai.VoiceState
import com.jarvis.launcher.ui.components.ArcReactor
import com.jarvis.launcher.ui.components.hudFrame
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud

private val suggestions = listOf(
    "What's the weather?", "Top headlines", "System status", "Set a timer for 5 minutes",
    "Who is Ada Lovelace?", "Bitcoin price", "Convert 100 usd to eur", "Flashlight on", "Tell me a joke",
)

@Composable
fun ConsoleScreen(
    messages: List<ChatMessage>,
    mode: CoreMode,
    voice: VoiceState,
    llm: LlmState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val hud = LocalHud.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp)) { ArcReactor(mode, voice.level, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("J.A.R.V.I.S CONSOLE", style = HudType.title, color = hud.primary)
                Text(
                    when (llm.status) {
                        CoreStatus.READY -> "NEURAL CORE · ${llm.modelName?.uppercase()?.take(28)}"
                        CoreStatus.LOADING -> "NEURAL CORE · BOOTING…"
                        CoreStatus.ERROR -> "NEURAL CORE · FAULT"
                        CoreStatus.NOT_INSTALLED -> "COMMAND MODE · 100% OFFLINE"
                    },
                    style = HudType.label, color = hud.textDim, maxLines = 1,
                )
            }
            IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteSweep, "Clear", tint = hud.textDim) }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = hud.primary) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { MessageBubble(it) }
            if (voice.partial.isNotBlank()) {
                item(key = "partial") {
                    MessageBubble(ChatMessage(-1, true, voice.partial + " …", source = ChatMessage.Source.USER), ghost = true)
                }
            }
        }

        if (messages.size <= 2) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
                items(suggestions) { s ->
                    Text(
                        s, color = hud.primary, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, hud.primary.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .clickable { onSend(s) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        voice.error?.let {
            Text(it, style = HudType.label, color = hud.warn, modifier = Modifier.padding(bottom = 6.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .hudFrame(hud, cut = 10.dp, fillAlpha = 0.1f)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (input.isEmpty()) Text("Type a command…", style = HudType.body, color = hud.textDim)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = HudType.body.copy(color = hud.text),
                    cursorBrush = SolidColor(hud.primary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) { onSend(input); input = "" }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            val busy = mode == CoreMode.THINKING || mode == CoreMode.LISTENING || mode == CoreMode.SPEAKING
            val pulse by rememberInfiniteTransition(label = "p").animateFloat(0.5f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(hud.primary.copy(alpha = if (busy) 0.25f * pulse else 0.15f))
                    .border(1.5.dp, hud.primary, CircleShape)
                    .clickable {
                        when {
                            input.isNotBlank() -> { onSend(input); input = "" }
                            busy -> onStop()
                            else -> onMic()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val icon = when {
                    input.isNotBlank() -> Icons.AutoMirrored.Rounded.Send
                    busy -> Icons.Rounded.Stop
                    else -> Icons.Rounded.Mic
                }
                Icon(icon, null, tint = hud.primary, modifier = Modifier.alpha(if (busy) pulse else 1f))
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, ghost: Boolean = false) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .alpha(if (ghost) 0.6f else 1f)
                .then(
                    if (m.fromUser) Modifier
                        .clip(RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp))
                        .background(hud.primary.copy(alpha = 0.16f))
                    else Modifier.hudFrame(hud, cut = 8.dp, fillAlpha = 0.05f, accent = if (m.source == ChatMessage.Source.NEURAL) hud.secondary else hud.primary)
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (!m.fromUser) {
                Text(
                    when (m.source) {
                        ChatMessage.Source.NEURAL -> "JARVIS · NEURAL"
                        ChatMessage.Source.SYSTEM -> "JARVIS · SYSTEM"
                        ChatMessage.Source.WEB -> "JARVIS · UPLINK"
                        else -> "JARVIS · COMMAND"
                    },
                    style = HudType.label.copy(fontSize = 8.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(3.dp))
            }
            val text = if (m.streaming && m.text.isEmpty()) "▍" else if (m.streaming) m.text + " ▍" else m.text
            Text(text, style = HudType.body, color = hud.text)
        }
    }
}
