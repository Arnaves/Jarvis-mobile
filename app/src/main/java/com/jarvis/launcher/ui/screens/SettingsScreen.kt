package com.jarvis.launcher.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.launcher.BuildConfig
import com.jarvis.launcher.JarvisApp
import com.jarvis.launcher.ai.CoreStatus
import com.jarvis.launcher.ui.components.BarMeter
import com.jarvis.launcher.ui.components.HudPanel
import com.jarvis.launcher.ui.components.hudFrame
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.HudPalettes
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    app: JarvisApp,
    isDefaultLauncher: Boolean,
    onSetDefault: () -> Unit,
    onImportModel: () -> Unit,
    onRequestPermission: (String) -> Unit,
    onClose: () -> Unit,
) {
    val hud = LocalHud.current
    val ctx = LocalContext.current
    val prefs = app.prefs
    val theme by prefs.theme.collectAsState()
    val voice by prefs.voiceReplies.collectAsState()
    val title by prefs.userTitle.collectAsState()
    val gpu by prefs.useGpu.collectAsState()
    val seconds by prefs.showSeconds.collectAsState()
    val notes by prefs.notes.collectAsState()
    val llm by app.llm.state.collectAsState()
    val gem by app.gemini.state.collectAsState()
    val handsFree by prefs.handsFree.collectAsState()
    val lockScreen by prefs.lockScreen.collectAsState()
    val webOn by prefs.webEnabled.collectAsState()
    val hfState by app.handsFree.state.collectAsState()
    val engine by app.handsFree.engineLabel.collectAsState()
    val wakeProgress by app.handsFree.models.progress.collectAsState()
    val lastHeard by app.handsFree.lastHeard.collectAsState()
    val sensitivity by prefs.wakeSensitivity.collectAsState()
    val wakeWord by prefs.wakeWord.collectAsState()
    val wakeName = com.jarvis.launcher.data.Prefs.wakeDisplay(wakeWord)
    var wakeError by remember { mutableStateOf<String?>(null) }
    val tasks by app.tasks.tasks.collectAsState()
    val places by app.tasks.places.collectAsState()
    val owner by prefs.ownerName.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val models = remember(refresh, llm) { app.llm.installedModels() }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadModel(f: File) {
        prefs.setModelPath(f.absolutePath)
        app.appScope.launch { app.llm.load(f.absolutePath, prefs.useGpu.value) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SYSTEM CONFIGURATION", style = HudType.title, color = hud.primary, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = hud.primary) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ------------------------------------------------ Launcher
            HudPanel(Modifier.fillMaxWidth(), title = "Home launcher") {
                Text(
                    if (isDefaultLauncher) "JARVIS is your default home screen." else "JARVIS isn't your default home screen yet.",
                    style = HudType.body, color = hud.text,
                )
                if (!isDefaultLauncher) {
                    Spacer(Modifier.height(10.dp))
                    HudButton("SET AS DEFAULT", onClick = onSetDefault)
                }
                Spacer(Modifier.height(10.dp))
                ToggleRow("Show seconds on clock", seconds) { prefs.setShowSeconds(it) }
            }

            // ------------------------------------------------ Theme
            HudPanel(Modifier.fillMaxWidth(), title = "HUD colour scheme") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudPalettes.all.forEach { p ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .border(if (p.id == theme) 2.dp else 1.dp, p.primary.copy(alpha = if (p.id == theme) 1f else 0.4f), RoundedCornerShape(50))
                                .clickable { prefs.setTheme(p.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(p.primary))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(p.secondary))
                            Spacer(Modifier.width(8.dp))
                            Text(p.title, color = hud.text, fontSize = 12.sp)
                        }
                    }
                }
            }

            // ------------------------------------------------ Voice
            HudPanel(Modifier.fillMaxWidth(), title = "Voice & personality") {
                ToggleRow("Spoken replies", voice) { prefs.setVoiceReplies(it) }
                Spacer(Modifier.height(10.dp))
                Text("JARVIS addresses you as", style = HudType.label, color = hud.textDim)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("sir", "ma'am", "boss", "chief", "captain").forEach { t ->
                        Chip(t, selected = title == t) { prefs.setUserTitle(t) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HudField(title, "Or type a name…") { prefs.setUserTitle(it) }
                Spacer(Modifier.height(10.dp))
                val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val contactsOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (micOk) "MIC ✓" else "GRANT MIC", enabled = !micOk) { onRequestPermission(Manifest.permission.RECORD_AUDIO) }
                    HudButton(if (contactsOk) "CONTACTS ✓" else "GRANT CONTACTS", enabled = !contactsOk) { onRequestPermission(Manifest.permission.READ_CONTACTS) }
                }
            }

            // ------------------------------------------------ Gemini
            HudPanel(Modifier.fillMaxWidth(), title = "AI brain · Google Gemini", accent = hud.secondary) {
                Text(
                    "Paste your Gemini API key (from Google AI Studio). It's stored encrypted with this phone's hardware keystore " +
                        "and only ever sent to Google. With it, JARVIS answers anything, with live Google Search, remembers the " +
                        "conversation and uses your notes. Phone commands still run on-device.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(10.dp))
                var keyInput by remember { mutableStateOf("") }
                var verifying by remember { mutableStateOf(false) }
                if (!gem.hasKey) {
                    HudField(keyInput, "Gemini API key (AIza…)", secret = true) { keyInput = it.trim() }
                    Spacer(Modifier.height(8.dp))
                    HudButton("SAVE & VERIFY", enabled = keyInput.length >= 20 && !verifying) {
                        app.gemini.setKey(keyInput)
                        keyInput = ""
                        verifying = true
                        app.appScope.launch { app.gemini.verify(); verifying = false }
                    }
                } else {
                    Text(
                        (if (gem.verified) "● " else "○ ") + gem.status.uppercase(),
                        style = HudType.data, color = if (gem.verified) hud.secondary else hud.warn,
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("Use Gemini", gem.enabled) { app.gemini.setEnabled(it) }
                    if (gem.models.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("MODEL", style = HudType.label, color = hud.textDim)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            gem.models.take(8).forEach { m -> Chip(m, selected = gem.model == m) { app.gemini.setModel(m) } }
                        }
                    } else {
                        Text("MODEL · ${gem.model}", style = HudType.label, color = hud.textDim)
                    }
                    gem.activeModel?.takeIf { it != gem.model }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("NOW ANSWERING · $it (auto-failover)", style = HudType.label, color = hud.warn)
                    }
                    Text(
                        "If a model runs out of quota, JARVIS automatically switches to the next one with quota left (flash-lite → flash → others).",
                        style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("BRAIN SWITCHBOARD", style = HudType.label, color = hud.textDim)
                    Spacer(Modifier.height(6.dp))
                    val brainMode by prefs.brainMode.collectAsState()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Auto", selected = brainMode == "AUTO") { prefs.setBrainMode("AUTO") }
                        listOf(com.jarvis.launcher.ai.Brain.CHAT, com.jarvis.launcher.ai.Brain.THINK, com.jarvis.launcher.ai.Brain.CODE).forEach { b ->
                            Chip(b.label, selected = brainMode == b.name) { prefs.setBrainMode(b.name) }
                        }
                    }
                    Text(
                        "Auto routes each request: CHAT for everyday questions, THINK for plans, maths and decisions (tap ▸ REASONING " +
                            "in the console to see its thinking), CODE for programming, VISION for photos and \"what's on my screen?\".",
                        style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HudButton(if (verifying) "VERIFYING…" else "VERIFY", enabled = !verifying) {
                            verifying = true
                            app.appScope.launch { app.gemini.verify(); verifying = false }
                        }
                        HudButton("REMOVE KEY") { app.gemini.clearKey() }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Privacy: when Gemini answers, your question, recent conversation, saved notes and the time/battery are sent to Google.",
                    style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                )
            }

            // ------------------------------------------------ Hands-free + lock screen
            HudPanel(Modifier.fillMaxWidth(), title = "Hands-free · \"$wakeName\"", accent = hud.secondary) {
                ToggleRow("Always listen for \"$wakeName\"", handsFree) { on ->
                    prefs.setHandsFree(on)
                    if (on) onRequestPermission(Manifest.permission.RECORD_AUDIO)
                    com.jarvis.launcher.service.JarvisService.sync(ctx)
                }
                Text(
                    "Say \"$wakeName\". He answers \"Yes, $title?\" and listens for 5 seconds, then goes back to standby. " +
                        "Works with the screen off and over other apps. You can also say it in one breath: \"$wakeName, what's the weather?\"",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "STATUS · ${hfState.name}" + (engine?.let { " · ${it.uppercase()}" } ?: ""),
                    style = HudType.label, color = if (hfState == com.jarvis.launcher.ai.HandsFree.State.OFF) hud.textDim else hud.secondary,
                )
                Spacer(Modifier.height(10.dp))
                Text("WAKE WORD", style = HudType.label, color = hud.textDim)
                Spacer(Modifier.height(6.dp))
                var wakeInput by remember(wakeWord) { mutableStateOf(wakeWord) }
                var wakeMsg by remember { mutableStateOf<String?>(null) }
                HudField(wakeInput, "e.g. jarvis, friday, edith, computer") { wakeInput = it; wakeMsg = null }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cleaned = wakeInput.lowercase().replace(Regex("[^\\p{L} ]"), " ").replace(Regex("\\s+"), " ").trim()
                    val words = cleaned.split(' ').filter { it.isNotBlank() }
                    val valid = words.size in 1..3 && cleaned.length >= 3 && words.none { it in setOf("hey", "ok", "okay", "hi", "hello") && words.size == 1 }
                    HudButton("SAVE WAKE WORD", enabled = valid && cleaned != wakeWord) {
                        app.appScope.launch {
                            val unknown = app.handsFree.models.unknownWords(words)
                            if (unknown.isNotEmpty() && app.handsFree.models.ready) {
                                wakeMsg = "The offline engine doesn't know \"${unknown.joinToString(" ")}\". Try another word (or it will rely on the system recogniser)."
                            } else {
                                prefs.setWakeWord(cleaned)
                                wakeMsg = "Saved. Say \"${com.jarvis.launcher.data.Prefs.wakeDisplay(cleaned)}\"."
                            }
                        }
                    }
                    if (wakeWord != com.jarvis.launcher.data.Prefs.DEFAULT_WAKE) {
                        HudButton("RESET") { prefs.setWakeWord(com.jarvis.launcher.data.Prefs.DEFAULT_WAKE); wakeMsg = "Back to \"Hey Jarvis\"." }
                    }
                }
                wakeMsg?.let { Text(it, style = HudType.body.copy(fontSize = 12.sp), color = if (it.startsWith("Saved") || it.startsWith("Back")) hud.secondary else hud.warn) }
                Text(
                    "Pick a distinctive word with 2+ syllables (\"Friday\", \"Edith\", \"Computer\"). " +
                        "One word means you say \"Hey <word>\"; for two or three words, say them exactly.",
                    style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(10.dp))
                Text("WAKE SENSITIVITY", style = HudType.label, color = hud.textDim)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.jarvis.launcher.ai.WakeSensitivity.entries.forEach { ws ->
                        Chip(ws.label, selected = sensitivity == ws.name) { prefs.setWakeSensitivity(ws.name) }
                    }
                }
                Text(
                    "Not responding? Choose Sensitive. Waking up by itself? Choose Strict.",
                    style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                )
                if (handsFree) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().hudFrame(hud, cut = 6.dp, fillAlpha = 0.05f).padding(10.dp)) {
                        Column {
                            Text("LIVE · WHAT JARVIS HEARS", style = HudType.label, color = hud.textDim)
                            Text(
                                lastHeard.ifBlank { "Say \"$wakeName\"…" },
                                style = HudType.data, color = if (lastHeard.startsWith("✓")) hud.secondary else hud.text, maxLines = 2,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (app.handsFree.models.ready) {
                    Text("WAKE ENGINE · OFFLINE KEYWORD SPOTTER INSTALLED", style = HudType.label, color = hud.secondary)
                } else {
                    Text(
                        "Install the silent offline wake engine (40 MB, one-time). Until then JARVIS uses the system recogniser, which may chime on some phones.",
                        style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                    )
                    Spacer(Modifier.height(8.dp))
                    wakeProgress?.let {
                        BarMeter(it, Modifier.fillMaxWidth().height(6.dp)); Spacer(Modifier.height(6.dp))
                    }
                    HudButton(if (wakeProgress != null) "INSTALLING…" else "INSTALL WAKE ENGINE", enabled = wakeProgress == null) {
                        wakeError = null
                        app.appScope.launch {
                            if (app.handsFree.models.download()) app.handsFree.reload()
                            else wakeError = "Download failed. Check your connection."
                        }
                    }
                    wakeError?.let { Text(it, style = HudType.label, color = hud.warn) }
                }
                Spacer(Modifier.height(14.dp))
                ToggleRow("JARVIS lock screen", lockScreen) { on ->
                    prefs.setLockScreen(on)
                    if (on && android.os.Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(ctx)) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        }
                    }
                    com.jarvis.launcher.service.JarvisService.sync(ctx)
                }
                val maker = android.os.Build.MANUFACTURER.lowercase()
                if (lockScreen && (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco"))) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Xiaomi/Redmi/Poco: also enable \"Show on Lock screen\" and \"Display pop-up windows while running in background\", otherwise JARVIS only appears after you unlock.",
                        style = HudType.body.copy(fontSize = 13.sp), color = hud.warn,
                    )
                    Spacer(Modifier.height(6.dp))
                    HudButton("OPEN MIUI PERMISSIONS") {
                        val miui = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
                            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            .putExtra("extra_pkgname", ctx.packageName)
                        runCatching { ctx.startActivity(miui) }.onFailure {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                } else if (lockScreen && (maker.contains("oppo") || maker.contains("vivo") || maker.contains("realme") || maker.contains("oneplus") || maker.contains("huawei") || maker.contains("honor"))) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "On ${android.os.Build.MANUFACTURER} phones, also allow \"Lock screen display\" / \"Show on lock screen\" for JARVIS in App info › Permissions.",
                        style = HudType.body.copy(fontSize = 13.sp), color = hud.warn,
                    )
                    Spacer(Modifier.height(6.dp))
                    HudButton("OPEN APP INFO") {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")))
                    }
                }
                Text(
                    "Shows the JARVIS HUD each time the screen turns on. Needs \"Display over other apps\". " +
                        "Your PIN or fingerprint still protects the phone; swipe up to hand over to it.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
            }

            // ------------------------------------------------ Phone control
            HudPanel(Modifier.fillMaxWidth(), title = "Phone control") {
                val a11yOn = com.jarvis.launcher.service.JarvisAccessibility.enabled(ctx)
                val a11yLive = com.jarvis.launcher.service.JarvisAccessibility.connected
                val notifOn = com.jarvis.launcher.service.JarvisNotificationListener.granted(ctx)
                val writeOn = android.provider.Settings.System.canWrite(ctx)
                val direct by prefs.directMessage.collectAsState()
                val roman by prefs.romanLetters.collectAsState()
                Text(
                    "Screen control lets JARVIS read the screen, tap, type, scroll, swipe, go back/home, lock the phone, take screenshots, " +
                        "send WhatsApp messages and use YouTube buttons, only when you ask.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        a11yLive -> "● SCREEN CONTROL CONNECTED"
                        a11yOn -> "○ ENABLED BUT NOT CONNECTED: toggle it off and on in Accessibility"
                        else -> "○ SCREEN CONTROL OFF"
                    },
                    style = HudType.label, color = if (a11yLive) hud.secondary else hud.warn,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (a11yLive) "SCREEN CONTROL ✓" else "ENABLE SCREEN CONTROL") {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    HudButton(if (notifOn) "MEDIA ACCESS ✓" else "ALLOW MEDIA ACCESS", enabled = !notifOn) {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    HudButton(if (writeOn) "BRIGHTNESS ✓" else "ALLOW BRIGHTNESS", enabled = !writeOn) {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                ToggleRow("WhatsApp: send directly (no \"send it\" step)", direct) { prefs.setDirectMessage(it) }
                Text(
                    "Off: JARVIS drafts the message and waits for \"send it\" or \"cancel\". \"Draft a WhatsApp…\" never sends.",
                    style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(6.dp))
                ToggleRow("Write messages in Roman letters", roman) { prefs.setRomanLetters(it) }
                Text(
                    "Speak Hindi, Marathi, Bengali, Tamil… and the message is written in English letters: \"kya kar rahe ho\".",
                    style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                )
            }

            // ------------------------------------------------ Tasks & automation
            HudPanel(Modifier.fillMaxWidth(), title = "Tasks & automation") {
                Text(
                    "Say things like \"send a WhatsApp to Mummy saying reached office when I reach office\", " +
                        "\"at 6 pm text Rahul saying running late\" or \"remind me to buy milk when I leave work\". " +
                        "Teach me places by saying \"save this place as office\" while you're there.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(10.dp))
                HudField(owner, "Your name (used in booking scripts)") { prefs.setOwnerName(it) }
                Spacer(Modifier.height(10.dp))
                val fineOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val bgOk = app.tasks.hasBackgroundLocation()
                val smsOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                val a11yOk = com.jarvis.launcher.service.JarvisAccessibility.enabled(ctx)
                val overlayOk = android.os.Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(ctx)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton(if (bgOk) "LOCATION ALL THE TIME ✓" else "ALLOW LOCATION ALL THE TIME", enabled = !bgOk) {
                        if (!fineOk) onRequestPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        else if (android.os.Build.VERSION.SDK_INT >= 29) onRequestPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    HudButton(if (smsOk) "AUTO-SEND SMS ✓" else "ALLOW AUTO-SEND SMS", enabled = !smsOk) {
                        onRequestPermission(Manifest.permission.SEND_SMS)
                    }
                    HudButton(if (a11yOk) "SCREEN CONTROL ✓" else "ENABLE SCREEN CONTROL", enabled = !a11yOk) {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    HudButton(if (overlayOk) "RUN IN BACKGROUND ✓" else "ALLOW RUNNING TASKS IN BACKGROUND", enabled = !overlayOk) {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
                        val exactOk = am.canScheduleExactAlarms()
                        HudButton(if (exactOk) "EXACT TIMING ✓" else "ALLOW EXACT TIMING", enabled = !exactOk) {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}")))
                            }
                        }
                    }
                }
                if (!a11yOk) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Screen control: in Accessibility, open \"JARVIS\" and switch it on. Needed to send WhatsApp messages automatically.",
                        style = HudType.body.copy(fontSize = 12.sp), color = hud.textDim,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("SAVED PLACES", style = HudType.label, color = hud.textDim)
                if (places.isEmpty()) Text("None yet.", style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim)
                places.forEach { pl ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("◉ ${pl.name}", style = HudType.body.copy(fontSize = 14.sp), color = hud.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { app.tasks.deletePlace(pl.name) }) { Icon(Icons.Outlined.Delete, "Delete", tint = hud.warn) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("PENDING TASKS", style = HudType.label, color = hud.textDim)
                if (tasks.isEmpty()) Text("None.", style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim)
                tasks.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.describe(), style = HudType.body.copy(fontSize = 13.sp), color = hud.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { app.tasks.cancel(t.id) }) { Icon(Icons.Outlined.Delete, "Cancel", tint = hud.warn) }
                    }
                }
            }

            // ------------------------------------------------ Internet
            HudPanel(Modifier.fillMaxWidth(), title = "Internet knowledge") {
                ToggleRow("Answer with live data", webOn) { prefs.setWebEnabled(it) }
                Text(
                    "Weather, news headlines, facts (Wikipedia / DuckDuckGo), crypto prices and currency conversion. " +
                        "Only the question is sent; no account or API key needed.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(8.dp))
                val locOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                HudButton(if (locOk) "LOCAL WEATHER ✓" else "ALLOW LOCATION FOR WEATHER", enabled = !locOk) {
                    onRequestPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }

            // ------------------------------------------------ Neural core
            HudPanel(Modifier.fillMaxWidth(), title = "Offline core (optional)") {
                val statusColor = when (llm.status) {
                    CoreStatus.READY -> hud.secondary
                    CoreStatus.ERROR -> hud.warn
                    CoreStatus.LOADING -> hud.warn
                    CoreStatus.NOT_INSTALLED -> hud.textDim
                }
                Text(llm.message.uppercase(), style = HudType.data, color = statusColor)
                llm.modelName?.let { Text(it, style = HudType.label, color = hud.textDim) }
                llm.transferProgress?.let { p ->
                    Spacer(Modifier.height(8.dp))
                    Text(if (p < 0) "TRANSFERRING…" else "TRANSFERRING ${(p * 100).toInt()}%", style = HudType.label, color = hud.primary)
                    Spacer(Modifier.height(4.dp))
                    BarMeter(if (p < 0) 0.5f else p, Modifier.fillMaxWidth().height(8.dp))
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, style = HudType.label, color = hud.warn) }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Optional offline brain: a MediaPipe .task model file stored on this phone, used when Gemini is " +
                        "unavailable (no internet). Import one you already have on the device.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(12.dp))

                if (models.isNotEmpty()) {
                    Text("INSTALLED MODELS", style = HudType.label, color = hud.textDim)
                    Spacer(Modifier.height(6.dp))
                    models.forEach { f ->
                        val active = llm.modelName == f.name && llm.status == CoreStatus.READY
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .hudFrame(hud, cut = 6.dp, fillAlpha = if (active) 0.12f else 0.03f, accent = if (active) hud.secondary else hud.primary)
                                .clickable(enabled = llm.status != CoreStatus.LOADING) { loadModel(f) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = hud.text, fontSize = 13.sp, maxLines = 1)
                                Text(
                                    "%.0f MB · %s".format(f.length() / 1_048_576f, if (active) "ACTIVE" else "TAP TO LOAD"),
                                    style = HudType.label, color = if (active) hud.secondary else hud.textDim,
                                )
                            }
                            IconButton(onClick = { app.llm.delete(f); if (prefs.modelPath.value == f.absolutePath) prefs.setModelPath(null); refresh++ }) {
                                Icon(Icons.Outlined.Delete, "Delete", tint = hud.warn)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("IMPORT MODEL FILE", enabled = llm.transferProgress == null, onClick = onImportModel)
                    if (llm.status == CoreStatus.READY) HudButton("UNLOAD") { app.llm.unload() }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow("GPU acceleration (faster, model must support it)", gpu) {
                    prefs.setUseGpu(it)
                    prefs.modelPath.value?.let { p -> app.appScope.launch { app.llm.load(p, it) } }
                }
                Spacer(Modifier.height(12.dp))
                Text("OR DOWNLOAD DIRECTLY", style = HudType.label, color = hud.textDim)
                Spacer(Modifier.height(6.dp))
                HudField(url, "https://…/model.task") { url = it.trim() }
                Spacer(Modifier.height(6.dp))
                HudField(token, "Access token (optional, for gated models)", secret = true) { token = it.trim() }
                Spacer(Modifier.height(8.dp))
                HudButton("DOWNLOAD", enabled = url.startsWith("https://") && llm.transferProgress == null) {
                    error = null
                    app.appScope.launch {
                        runCatching { app.llm.download(url, token) }
                            .onSuccess { path -> refresh++; loadModel(File(path)) }
                            .onFailure { error = "Download failed: ${it.message}" }
                    }
                }
            }

            // ------------------------------------------------ Notes
            HudPanel(Modifier.fillMaxWidth(), title = "Notes & briefing") {
                Text(
                    "Say \"note for tomorrow: call the bank\" or \"remember that the Wi-Fi password is…\". " +
                        "Ask \"what do you have for me today?\" for a briefing with today's notes, tasks, weather and alarms.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
                Spacer(Modifier.height(8.dp))
                if (notes.isEmpty()) {
                    Text("No notes yet.", style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim)
                } else {
                    notes.takeLast(15).reversed().forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.text, style = HudType.body.copy(fontSize = 14.sp), color = hud.text)
                                n.forDay?.let { Text("FOR ${com.jarvis.launcher.ai.NoteDates.label(it).uppercase()}", style = HudType.label, color = hud.secondary) }
                            }
                            IconButton(onClick = { prefs.removeNote(n) }) { Icon(Icons.Outlined.Delete, "Delete", tint = hud.warn) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    HudButton("DELETE ALL NOTES") { prefs.clearMemories() }
                }
            }

            // ------------------------------------------------ About
            HudPanel(Modifier.fillMaxWidth(), title = "About") {
                Text("JARVIS Launcher v${BuildConfig.VERSION_NAME}", style = HudType.data, color = hud.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Privacy: voice recognition, the wake word, notes and the offline core stay on this device. " +
                        "Gemini answers and web lookups send only what's needed for that request.",
                    style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim,
                )
            }
        }
    }
}

@Composable
fun HudButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val hud = LocalHud.current
    Box(
        Modifier
            .hudFrame(hud, cut = 6.dp, fillAlpha = if (enabled) 0.14f else 0.03f, accent = if (enabled) hud.primary else hud.textDim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontFamily = HudMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp, color = if (enabled) hud.primary else hud.textDim)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val hud = LocalHud.current
    Text(
        label, color = if (selected) Color.Black else hud.primary, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) hud.primary else Color.Transparent)
            .border(1.dp, hud.primary.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val hud = LocalHud.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = HudType.body.copy(fontSize = 14.sp), color = hud.text, modifier = Modifier.weight(1f))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = hud.primary,
                uncheckedThumbColor = hud.textDim, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = hud.dim,
            ),
        )
    }
}

@Composable
private fun HudField(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val hud = LocalHud.current
    Box(
        Modifier
            .fillMaxWidth()
            .hudFrame(hud, cut = 6.dp, fillAlpha = 0.06f)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = HudType.body.copy(fontSize = 13.sp), color = hud.textDim)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = HudType.body.copy(fontSize = 13.sp, color = hud.text),
            cursorBrush = SolidColor(hud.primary),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
