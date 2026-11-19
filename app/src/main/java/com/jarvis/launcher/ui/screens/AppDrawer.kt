package com.jarvis.launcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.launcher.data.AppEntry
import com.jarvis.launcher.data.AppRepository
import com.jarvis.launcher.ui.components.AppIcon
import com.jarvis.launcher.ui.components.hudFrame
import com.jarvis.launcher.ui.theme.HudMono
import com.jarvis.launcher.ui.theme.HudType
import com.jarvis.launcher.ui.theme.LocalHud

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    repo: AppRepository,
    apps: List<AppEntry>,
    pinned: List<String>,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
    onAskJarvis: (String) -> Unit,
    onClose: () -> Unit,
) {
    val hud = LocalHud.current
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else {
            val q = AppRepository.normalize(query)
            apps.filter { AppRepository.normalize(it.label).contains(q) || it.packageName.contains(q.replace(" ", "")) }
                .sortedBy { if (AppRepository.normalize(it.label).startsWith(q)) 0 else 1 }
        }
    }

    // Pull down at top of list closes the drawer.
    val closeOnOverscroll = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) {
                    pulled += available.y
                    if (pulled > 220f) { pulled = 0f; onClose() }
                } else pulled = 0f
                return Offset.Zero
            }
        }
    }


    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("APPLICATION INDEX", style = HudType.title, color = hud.primary, modifier = Modifier.weight(1f))
            Text("${apps.size} MODULES", style = HudType.label, color = hud.textDim)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = hud.primary) }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .hudFrame(hud, cut = 10.dp, fillAlpha = 0.1f)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = hud.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search apps or ask JARVIS…", style = HudType.body, color = hud.textDim)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = HudType.body.copy(color = hud.text),
                    cursorBrush = SolidColor(hud.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val first = filtered.firstOrNull()
                        if (first != null) onLaunch(first) else if (query.isNotBlank()) onAskJarvis(query)
                        keyboard?.hide()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Close, "Clear", tint = hud.textDim,
                    modifier = Modifier.size(18.dp).combinedClickable(onClick = { query = "" }),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            modifier = Modifier.fillMaxSize().nestedScroll(closeOnOverscroll),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (query.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .hudFrame(hud, cut = 8.dp, fillAlpha = 0.05f, accent = hud.secondary)
                            .combinedClickable(onClick = { onAskJarvis(query) })
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ASK JARVIS ›", style = HudType.label, color = hud.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text(query, style = HudType.body, color = hud.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            items(filtered, key = { it.key + it.user.hashCode() }) { app ->
                DrawerItem(repo, app, app.key in pinned, onLaunch, onTogglePin)
            }
            if (filtered.isEmpty() && query.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "NO MATCHING MODULES", style = HudType.label, color = hud.textDim,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    repo: AppRepository,
    app: AppEntry,
    pinned: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onTogglePin: (AppEntry) -> Unit,
) {
    val hud = LocalHud.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onLaunch(app) },
                    onLongClick = { menu = true },
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, repo, 56.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                app.label, color = if (pinned) hud.primary else hud.text, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.background(hud.bgBottom),
        ) {
            Text(
                app.label.uppercase(), style = HudType.label, color = hud.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin from dock" else "Pin to dock", color = hud.text, fontFamily = HudMono, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null, tint = hud.primary) },
                onClick = { menu = false; onTogglePin(app) },
            )
            DropdownMenuItem(
                text = { Text("App info", color = hud.text, fontFamily = HudMono, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Info, null, tint = hud.primary) },
                onClick = { menu = false; repo.openInfo(app) },
            )
            DropdownMenuItem(
                text = { Text("Uninstall", color = hud.warn, fontFamily = HudMono, fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = hud.warn) },
                onClick = { menu = false; repo.uninstall(app) },
            )
        }
    }
}
