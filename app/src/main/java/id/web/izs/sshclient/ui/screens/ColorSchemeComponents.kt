package id.web.izs.sshclient.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.web.izs.sshclient.core.config.IZS_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.TABBY_DEFAULT_SCHEME
import id.web.izs.sshclient.core.config.TerminalColorScheme
import id.web.izs.sshclient.core.config.parseTerminalColorScheme
import id.web.izs.sshclient.core.config.schemeColorArgb
import id.web.izs.sshclient.core.config.toSchemeMap
import org.json.JSONArray

/**
 * Shared color-scheme UI (Settings > Color scheme, profile editor Colours
 * tab, custom editor): asset loading, picker list with live preview rows,
 * and the sample terminal line. Scope is terminal content only — the app
 * chrome keeps the IzsDarkColors theme.
 */

/**
 * Built-in schemes from the curated asset (Izs Default + Tabby Default +
 * community picks). Falls back to the two in-code defaults when the asset
 * is missing/unparseable — the picker never comes up empty.
 */
fun loadBuiltinSchemes(context: Context): List<TerminalColorScheme> {
    return try {
        val json = context.assets.open("color_schemes.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        List(arr.length()) { parseTerminalColorScheme(arr.getJSONObject(it).toSchemeMap()) }
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?: listOf(IZS_DEFAULT_SCHEME, TABBY_DEFAULT_SCHEME)
    } catch (_: Exception) {
        listOf(IZS_DEFAULT_SCHEME, TABBY_DEFAULT_SCHEME)
    }
}

@Composable
fun rememberBuiltinSchemes(): List<TerminalColorScheme> {
    val context = LocalContext.current
    return remember { loadBuiltinSchemes(context) }
}

/** One `user@host:~$ ls` line rendered in the scheme (the picker preview). */
@Composable
fun SchemeSampleLine(scheme: TerminalColorScheme, modifier: Modifier = Modifier) {
    SchemeFullPreview(scheme = scheme, fontSizeSp = 11f, modifier = modifier)
}

/**
 * Desktop `color-scheme-preview` parity (colorSchemePreview.component.pug):
 * the `john@doe-pc $ ls` prompt (green @, blue host, bold red $, block
 * cursor) plus the Documents/Downloads/Pictures/Music rows that exercise
 * yellow-bold, black-on-green, black-on-bright, and bright-blue. Bad
 * schemes (the Atom class: Pictures unreadable) are VISIBLE here — that is
 * the point, same as desktop.
 */
@Composable
fun SchemeFullPreview(
    scheme: TerminalColorScheme,
    fontSizeSp: Float = 12f,
    modifier: Modifier = Modifier,
) {
    val preview = remember(scheme) {
        val fg = schemeColorArgb(scheme.foreground)?.let { Color(it) } ?: Color.White
        val bg = schemeColorArgb(scheme.background)?.let { Color(it) } ?: Color.Black
        val c = scheme.colors.mapNotNull { schemeColorArgb(it)?.let { cc -> Color(cc) } }
        val pick: (Int) -> Color = { c.getOrElse(it) { fg } }
        val bold = androidx.compose.ui.text.font.FontWeight.Bold
        Triple(bg, fg, buildAnnotatedString {
            withStyle(SpanStyle(color = pick(2))) { append("john") }
            withStyle(SpanStyle(color = pick(6))) { append("@") }
            withStyle(SpanStyle(color = pick(4))) { append("doe-pc") }
            withStyle(SpanStyle(color = fg)) { append(" ") }
            withStyle(SpanStyle(color = pick(1), fontWeight = bold)) { append("$") }
            withStyle(SpanStyle(color = fg)) { append(" ls ") }
            withStyle(SpanStyle(color = pick(0), background = schemeCursor(scheme))) { append(" ") }
            append("\n")
            withStyle(SpanStyle(color = fg)) { append("-rwxr-xr-x  1 root ") }
            withStyle(SpanStyle(color = pick(3), fontWeight = bold)) { append("Documents") }
            append("\n")
            withStyle(SpanStyle(color = fg)) { append("-rwxr-xr-x  1 root ") }
            withStyle(SpanStyle(color = pick(0), background = pick(2), fontWeight = bold)) { append("Downloads") }
            append("\n")
            withStyle(SpanStyle(color = fg)) { append("-rwxr-xr-x  1 root ") }
            withStyle(SpanStyle(color = pick(0), background = pick(8), fontWeight = bold)) { append("Pictures") }
            append("\n")
            withStyle(SpanStyle(color = fg)) { append("-rwxr-xr-x  1 root ") }
            withStyle(SpanStyle(color = pick(12), fontWeight = bold)) { append(" Music") }
        })
    }
    Text(
        text = preview.third,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 4).sp,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(preview.first)
            .padding(8.dp),
    )
}

private fun schemeCursor(scheme: TerminalColorScheme): Color =
    schemeColorArgb(scheme.cursor)?.let { Color(it) }
        ?: schemeColorArgb(scheme.foreground)?.let { Color(it) } ?: Color.White

/** A picker row: swatch + name + sample line, radio-selected on tap. */
@Composable
fun SchemeRow(
    scheme: TerminalColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    badge: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    scheme.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                trailing?.invoke() ?: RadioButton(selected = selected, onClick = onClick)
            }
            SchemeFullPreview(scheme = scheme, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Searchable scheme list shared by the global picker and the profile
 * Colours tab. [globalRow] renders an extra top row (e.g. "Use global
 * default" / "System default") when non-null.
 */
@Composable
fun SchemePickerList(
    schemes: List<TerminalColorScheme>,
    selected: TerminalColorScheme?,
    onSelect: (TerminalColorScheme?) -> Unit,
    globalRow: @Composable (() -> Unit)? = null,
    emptyHint: String = "No schemes found.",
    badgeFor: (TerminalColorScheme) -> String? = { null },
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(schemes, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) schemes else schemes.filter { it.name.lowercase().contains(q) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (schemes.size > 8) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search colour schemes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        globalRow?.invoke()
        if (filtered.isEmpty()) {
            Text(emptyHint, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                // Bounded: this picker also lives inside scrolling parents
                // (profile editor tab) where weight is meaningless — an
                // unbounded LazyColumn would compose every row eagerly.
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.name + it.background }) { s ->
                    SchemeRow(
                        scheme = s,
                        selected = s == selected,
                        onClick = { onSelect(s) },
                        badge = badgeFor(s),
                    )
                }
            }
        }
    }
}
