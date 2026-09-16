package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SOURCE_CODE_PRO_YAML_NAME
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.TerminalCursor
import id.web.izs.sshclient.core.config.TerminalFont
import id.web.izs.sshclient.core.config.isFallbackScheme
import id.web.izs.sshclient.core.config.parseSchemeJson
import id.web.izs.sshclient.core.config.parseSchemeSource
import id.web.izs.sshclient.core.config.resolveActiveScheme
import id.web.izs.sshclient.core.config.resolveTerminalFont
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.config.IZS_DEFAULT_LIGHT_SCHEME
import id.web.izs.sshclient.core.sync.SyncRepository
import id.web.izs.sshclient.data.local.ConfigDisk
import id.web.izs.sshclient.ui.AppPalettes
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.rememberAppDarkTheme
import kotlinx.coroutines.launch

/**
 * Settings > Appearance (desktop terminal-appearance + window-theme parity).
 *
 * - App theme: device-only (System/Dark/Light) — the desktop
 *   `appearance.*` keys describe a desktop window manager (frame,
 *   vibrancy, dock) with no phone equivalent, so this never syncs.
 * - Terminal font: `terminal.font` YAML (System = absent key = desktop
 *   default; Source Code Pro = the same bundled file as desktop's
 *   `monospace-fallback`). Renders live on this phone too.
 * - Font size: device-only (moved here from Settings > Terminal) —
 *   screens differ, syncing would resize the desktop on every pinch.
 * - Cursor shape + blink: `terminal.cursor`/`cursorBlink` YAML, live on
 *   open sessions (read per composition in TerminalScreen).
 *
 * Everything applies immediately — no Save button (desktop
 * `ngModelChange=config.save()` parity). The preview below renders with
 * the selected font + size through the same text style as the live grid,
 * so it matches by construction.
 */
@Composable
fun AppearanceSettingsScreen(
    state: AppState,
    onBack: () -> Unit,
) {
    var theme by remember { mutableStateOf(state.disk.appTheme) }
    var palette by remember { mutableStateOf(state.disk.appPalette) }
    var follow by remember { mutableStateOf(state.disk.followColorScheme) }
    var fontSp by remember { mutableFloatStateOf(state.disk.terminalFontSp) }
    val store = state.loaded?.store ?: emptyMap()
    var yamlFont by remember(state.loaded) {
        mutableStateOf(resolveTerminalFont(RawConfigStore.terminalFontName(store)))
    }
    var cursor by remember(state.loaded) {
        mutableStateOf(RawConfigStore.terminalCursor(store))
    }
    var blink by remember(state.loaded) {
        mutableStateOf(RawConfigStore.terminalCursorBlink(store))
    }
    // Preview scheme: the SAME resolution TerminalScreen uses (synced
    // global vs device scheme, profile n/a here, light fallback when
    // nothing is set) — so the preview paints exactly what the live
    // terminal will.
    val schemeSource = parseSchemeSource(state.disk.colorSchemeSource)
    val deviceScheme = parseSchemeJson(state.disk.localColorSchemeJson)
    val globalScheme = state.loaded?.domain?.terminalColorScheme
    val appDark = rememberAppDarkTheme(theme)
    val previewScheme = if (
        !appDark && isFallbackScheme(null, globalScheme, schemeSource, deviceScheme)
    ) {
        IZS_DEFAULT_LIGHT_SCHEME
    } else {
        resolveActiveScheme(null, globalScheme, schemeSource, deviceScheme)
    }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var pendingRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()

    // Disk prefs are not observable: re-read on resume so a theme/font
    // change is reflected on return (keyLayout parity).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                theme = state.disk.appTheme
                palette = state.disk.appPalette
                follow = state.disk.followColorScheme
                fontSp = state.disk.terminalFontSp
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun runWrite(write: suspend () -> SyncRepository.Loaded) {
        msg = null
        scope.launch {
            busy = true
            try {
                // Adopt the write's own Loaded (color-scheme parity): a
                // refresh() would re-decrypt + re-parse for nothing.
                state.adopt(write())
            } catch (e: IllegalStateException) {
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingRetry = { runWrite(write) }
                    showUnlock = true
                } else {
                    msg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                msg = "Couldn't save: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun commitFollow(v: Boolean) {
        follow = v
        state.disk.followColorScheme = v
        state.followColorScheme = v
    }

    fun commitTheme(v: String) {
        theme = v
        state.disk.appTheme = v
        state.themeMode = v
    }

    fun commitPalette(id: String) {
        palette = id
        state.disk.appPalette = id
        state.paletteName = id
    }

    fun commitFont(v: TerminalFont) {
        if (busy) return
        val old = yamlFont
        yamlFont = v
        runWrite {
            try {
                state.repo.updateTerminalSection { raw ->
                    RawConfigStore.setTerminalFont(
                        raw,
                        if (v == TerminalFont.SOURCE_CODE_PRO) SOURCE_CODE_PRO_YAML_NAME else null,
                    )
                }
            } catch (e: Exception) {
                yamlFont = old
                throw e
            }
        }
    }

    fun commitCursor(v: TerminalCursor) {
        if (busy) return
        val old = cursor
        cursor = v
        runWrite {
            try {
                state.repo.updateTerminalSection { raw -> RawConfigStore.setTerminalCursor(raw, v) }
            } catch (e: Exception) {
                cursor = old
                throw e
            }
        }
    }

    fun commitBlink(v: Boolean) {
        if (busy) return
        val old = blink
        blink = v
        runWrite {
            try {
                state.repo.updateTerminalSection { raw -> RawConfigStore.setTerminalCursorBlink(raw, v) }
            } catch (e: Exception) {
                blink = old
                throw e
            }
        }
    }

    @Composable
    fun RadioRow(
        selected: Boolean,
        enabled: Boolean,
        label: String,
        onClick: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = enabled) { onClick() }
                .padding(vertical = 4.dp),
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = onClick)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Appearance", onBack, busy = busy, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Text(
            "App theme plus terminal font and cursor. The terminal " +
                "colors are under Settings > Color scheme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("App theme", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = !busy) { commitFollow(!follow) }
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Follow color scheme",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Top bar, dialogs, buttons and forms follow the active color scheme " +
                        "(Tabby desktop parity). Dark/light is automatic; palettes below are ignored while on. " +
                        "Inside a session whose profile sets its own scheme, the terminal top bar, " +
                        "menus, SFTP and extra keys use that profile scheme; lists follow the active scheme.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = follow, enabled = !busy, onCheckedChange = { commitFollow(it) })
        }
        RadioRow(
            selected = theme == ConfigDisk.THEME_SYSTEM,
            enabled = !busy && !follow,
            label = "System (follows the phone theme)",
            onClick = { commitTheme(ConfigDisk.THEME_SYSTEM) },
        )
        RadioRow(
            selected = theme == ConfigDisk.THEME_DARK,
            enabled = !busy && !follow,
            label = "Dark",
            onClick = { commitTheme(ConfigDisk.THEME_DARK) },
        )
        RadioRow(
            selected = theme == ConfigDisk.THEME_LIGHT,
            enabled = !busy && !follow,
            label = "Light",
            onClick = { commitTheme(ConfigDisk.THEME_LIGHT) },
        )
        Text(
            "Stored only on this device. It is never synced.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("App colours", style = MaterialTheme.typography.titleMedium)
        // Accent palette for buttons, switches, tabs and highlights.
        // Terminal content is untouched (Settings > Color scheme owns
        // that); the terminal stage and key bar keep their own colors.
        for (p in AppPalettes) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !busy && !follow) { commitPalette(p.id) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = palette == p.id,
                    enabled = !busy && !follow,
                    onClick = { commitPalette(p.id) },
                )
                // Swatch: dark primary + light primary side by side, so
                // the dot reads in either app theme.
                Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp)) {
                    Box(
                        Modifier.size(16.dp).background(p.dark.primary, CircleShape),
                    )
                    Box(
                        Modifier.size(16.dp).background(p.light.primary, CircleShape),
                    )
                }
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!busy && !follow) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("Terminal font", style = MaterialTheme.typography.titleMedium)
        RadioRow(
            selected = yamlFont == TerminalFont.SYSTEM,
            enabled = !busy,
            label = "System monospace",
            onClick = { commitFont(TerminalFont.SYSTEM) },
        )
        RadioRow(
            selected = yamlFont == TerminalFont.SOURCE_CODE_PRO,
            enabled = !busy,
            label = "Source Code Pro (bundled)",
            onClick = { commitFont(TerminalFont.SOURCE_CODE_PRO) },
        )
        Text(
            "Synced. Desktop uses the same font when available. Other " +
                "desktop fonts appear as system monospace here and are " +
                "never changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Font size", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    fontSp = (fontSp - 1f).coerceIn(8f, 24f)
                    state.disk.terminalFontSp = fontSp
                },
            ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease font size") }
            Text(
                "${fontSp.toInt()}sp",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(
                onClick = {
                    fontSp = (fontSp + 1f).coerceIn(8f, 24f)
                    state.disk.terminalFontSp = fontSp
                },
            ) { Icon(Icons.Filled.Add, contentDescription = "Increase font size") }
        }
        Text(
            "Stored only on this device. It does not affect other devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Preview",
            style = MaterialTheme.typography.titleMedium,
        )
        FontPreview(font = yamlFont, fontSp = fontSp, scheme = previewScheme, cursor = cursor)
        Text("Cursor style", style = MaterialTheme.typography.titleMedium)
        // Desktop parity (appearanceSettingsTab.pug): direct shape
        // buttons (█ beam ❘ underline ▁), not text radios. Painted in
        // the active scheme cursor color on its background.
        val cursorColor = schemeColorArgb(previewScheme.cursor)?.let { Color(it) }
            ?: MaterialTheme.colorScheme.primary
        val cursorBg = schemeColorArgb(previewScheme.background)?.let { Color(it) }
            ?: MaterialTheme.colorScheme.surface
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CursorShapeButton(
                glyph = "█",
                label = "Block",
                selected = cursor == TerminalCursor.BLOCK,
                enabled = !busy,
                fg = cursorColor,
                bg = cursorBg,
                onClick = { commitCursor(TerminalCursor.BLOCK) },
            )
            CursorShapeButton(
                glyph = "❘",
                label = "Beam",
                selected = cursor == TerminalCursor.BEAM,
                enabled = !busy,
                fg = cursorColor,
                bg = cursorBg,
                onClick = { commitCursor(TerminalCursor.BEAM) },
            )
            CursorShapeButton(
                glyph = "▁",
                label = "Underline",
                selected = cursor == TerminalCursor.UNDERLINE,
                enabled = !busy,
                fg = cursorColor,
                bg = cursorBg,
                onClick = { commitCursor(TerminalCursor.UNDERLINE) },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = !busy) { commitBlink(!blink) }
                .padding(vertical = 4.dp),
        ) {
            Text(
                "Blink",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(checked = blink, enabled = !busy, onCheckedChange = { commitBlink(it) })
        }
        Text(
            "Synced. Applies to open sessions when you return.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                pendingRetry?.invoke()
                pendingRetry = null
            },
            onNoConfig = { showUnlock = false },
            onDismiss = { showUnlock = false; pendingRetry = null },
        )
    }
}

/**
 * Visual cursor-shape button (desktop btn-group parity): the shape
 * glyph in the scheme cursor color. Selected state uses the app
 * primary border + container.
 */
@Composable
private fun CursorShapeButton(
    glyph: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    fg: Color,
    bg: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(width = 52.dp, height = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else fg,
        )
    }
}

/**
 * Live font + size + cursor preview, painted with the ACTIVE terminal
 * scheme (same resolution as TerminalScreen) through the same text
 * style the grid draws with — what you see is what the session shows.
 * The cursor sits after the prompt in its selected shape (blink shown
 * steady; it blinks live).
 */
@Composable
private fun FontPreview(
    font: TerminalFont,
    fontSp: Float,
    scheme: TerminalColorScheme,
    cursor: TerminalCursor,
) {
    val family = rememberTerminalFontFamily(font)
    val style = terminalTextStyle(family, fontSp)
    val measurer = rememberTextMeasurer()
    // Same metrics as the grid: measured advance + 1.25x line height.
    val cell = rememberTerminalCell(fontSp, family)
    val (charW, lineH) = cell
    val bg = schemeColorArgb(scheme.background)?.let { androidx.compose.ui.graphics.Color(it) }
        ?: androidx.compose.ui.graphics.Color.Black
    val fg = schemeColorArgb(scheme.foreground)?.let { androidx.compose.ui.graphics.Color(it) }
        ?: androidx.compose.ui.graphics.Color.White
    val cur = schemeColorArgb(scheme.cursor)?.let { androidx.compose.ui.graphics.Color(it) } ?: fg
    val dir = scheme.colors.getOrNull(4)?.let { schemeColorArgb(it) }
        ?.let { androidx.compose.ui.graphics.Color(it) } ?: fg
    val prompt = buildAnnotatedString {
        withStyle(SpanStyle(color = fg)) { append("john@doe-pc $ ls") }
    }
    val dirLine1 = buildAnnotatedString {
        withStyle(SpanStyle(color = dir)) { append("Documents  Downloads") }
    }
    val dirLine2 = buildAnnotatedString {
        withStyle(SpanStyle(color = dir)) { append("Pictures   Music") }
    }
    val fileLine = buildAnnotatedString {
        withStyle(SpanStyle(color = fg)) { append("notes.txt  4.0K") }
    }
    val promptW = measurer.measure(prompt, style).size.width.toFloat()
    val pad = 12.dp
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(pad),
    ) {
        Canvas(
            modifier = Modifier.fillMaxWidth()
                .height(
                    with(
                        androidx.compose.ui.platform.LocalDensity.current,
                    ) { (lineH * 4).toDp() },
                ),
        ) {
            // AnnotatedString + span colors, exactly like the live
            // grid's drawTerminal (same measurer path, same style) —
            // plain-String drawText resolves colors differently and
            // rendered the prompt dimmer than the live terminal.
            drawText(
                textMeasurer = measurer,
                text = prompt,
                topLeft = Offset(0f, 0f),
                style = style,
            )
            drawText(
                textMeasurer = measurer,
                text = dirLine1,
                topLeft = Offset(0f, lineH),
                style = style,
            )
            drawText(
                textMeasurer = measurer,
                text = dirLine2,
                topLeft = Offset(0f, lineH * 2),
                style = style,
            )
            drawText(
                textMeasurer = measurer,
                text = fileLine,
                topLeft = Offset(0f, lineH * 3),
                style = style,
            )
            // Selected cursor shape after the prompt (same geometry as
            // the live overlay: full-cell block, left-edge beam,
            // bottom underline).
            when (cursor) {
                TerminalCursor.BEAM -> {
                    val w = 2.dp.toPx().coerceAtLeast(2f)
                    drawRect(cur, topLeft = Offset(promptW, 0f), size = androidx.compose.ui.geometry.Size(w, lineH))
                }
                TerminalCursor.UNDERLINE -> {
                    val h = (lineH * 0.12f).coerceAtLeast(2f)
                    drawRect(
                        cur,
                        topLeft = Offset(promptW, lineH - h),
                        size = androidx.compose.ui.geometry.Size(charW, h),
                    )
                }
                TerminalCursor.BLOCK -> {
                    drawRect(
                        cur,
                        topLeft = Offset(promptW, 0f),
                        size = androidx.compose.ui.geometry.Size(charW, lineH),
                    )
                }
            }
        }
    }
}
