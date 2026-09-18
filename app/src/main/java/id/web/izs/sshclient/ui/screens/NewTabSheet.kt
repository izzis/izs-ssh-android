package id.web.izs.sshclient.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.ui.AppState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fling velocity (px/s) that jumps the sheet to the next anchor. */
private const val SHEET_FLING_VELOCITY = 700f

/**
 * Quick-pick sheet for the terminal "+" button (Settings > Window >
 * New tab = sheet). Search + recent + compact profile list over the
 * current session — no navigation away from the terminal.
 */
/**
 * Quick-pick sheet for the terminal "+" button (Settings > Window >
 * New tab = sheet). Search + recent + compact profile list over the
 * current session — no navigation away from the terminal.
 *
 * Custom sheet (not ModalBottomSheet): the M3 sheet consumes upward drags
 * for its own anchor priority, so list drags intermittently moved the
 * sheet instead of scrolling. Here the ONLY drag affordance is the header
 * zone (pill + title + search, outside the scroll); the list is plain and
 * owns every drag inside it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewTabSheet(
    state: AppState,
    onPick: (profileId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var recentVersion by remember { mutableStateOf(0) }
    val profiles = remember(state.loaded) { state.displayProfiles() }
    val maxRecent = remember(state.loaded) {
        RawConfigStore.showRecentProfiles(state.loaded?.store ?: emptyMap())
    }
    val recent = remember(profiles, state.disk.recentProfileIds, recentVersion) {
        if (maxRecent <= 0) emptyList()
        else {
            val byId = profiles.associateBy { it.id }
            state.disk.recentProfileIds.take(maxRecent).mapNotNull { byId[it] }
        }
    }
    val filtered = remember(profiles, query) {
        if (query.isBlank()) profiles
        else profiles.filter {
            it.name.contains(query, true) ||
                it.options.host.contains(query, true) ||
                it.options.user.contains(query, true)
        }
    }
    // Desktop selector parity (profiles.service.ts): recents on top, then
    // profiles under sticky group headers — like the home list, the
    // outgoing header stays stuck while the next section arrives, so two
    // group names can show during the overlap. Ungrouped profiles have no
    // header. Searching keeps the same grouped view over the matches.
    val groups = remember(state.loaded) { state.displayGroups() }
    val sections = remember(profiles, groups) { toSections(profiles, groups) }
    val fSections = remember(filtered, groups) { toSections(filtered, groups) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = { /* scrim tap + back go through hide() below */ },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenH = with(LocalDensity.current) { maxHeight.toPx() }
            val halfY = screenH / 2f
            // Sheet offset: 0 = full, halfY = half, screenH = hidden.
            val offset = remember(screenH) { Animatable(screenH) }
            var gone by remember { mutableStateOf(false) }
            fun settle(target: Float, dismiss: Boolean) {
                scope.launch {
                    offset.animateTo(target, spring(stiffness = Spring.StiffnessMedium))
                    if (dismiss && !gone) {
                        gone = true
                        onDismiss()
                    }
                }
            }
            fun hide() = settle(screenH, true)
            // Enter at half (M3 sheet behavior).
            LaunchedEffect(screenH) {
                offset.animateTo(halfY, spring(stiffness = Spring.StiffnessMediumLow))
            }
            BackHandler { hide() }
            // Scrim fades with the sheet; tap dismisses with exit animation.
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * (1f - (offset.value / screenH).coerceIn(0f, 1f))))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { hide() },
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .requiredHeight(maxHeight)
                    .offset { IntOffset(0, offset.value.roundToInt()) },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    // Handle zone: pill + title + search, draggable to
                    // half/full/hidden. Everything scrollable lives below,
                    // so these drags never fight the list.
                    Column(
                        Modifier.fillMaxWidth().padding(top = 4.dp)
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { d ->
                                    scope.launch {
                                        offset.snapTo((offset.value + d).coerceIn(0f, screenH))
                                    }
                                },
                                onDragStopped = { v ->
                                    val target = when {
                                        v < -SHEET_FLING_VELOCITY -> if (offset.value > halfY) halfY else 0f
                                        v > SHEET_FLING_VELOCITY -> if (offset.value < halfY) halfY else screenH
                                        else -> listOf(0f, halfY, screenH).minBy { abs(it - offset.value) }
                                    }
                                    settle(target, target == screenH)
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(width = 32.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Text(
                            "New tab",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search by name, host, or user") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (query.isBlank() && recent.isNotEmpty()) {
                            item(key = "recent-header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Recent",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            state.disk.recentProfileIds = emptyList()
                                            recentVersion++
                                        },
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                            items(recent, key = { "recent:${it.id}" }) { p ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { onPick(p.id) }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                                        if (p.type == "ssh") {
                                            Text(
                                                SshDefaults.quickName(p.options.user, p.options.host, p.options.port),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (query.isBlank()) {
                            sheetSections(sections = sections, onPick = onPick)
                        } else {
                            // groupBy yields only groups with matches; an empty
                            // result shows a note instead of a blank sheet.
                            sheetSections(sections = fSections, onPick = onPick)
                            if (fSections.isEmpty()) {
                                item(key = "no-match") {
                                    Text(
                                        "No profiles match \"$query\".",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Profiles grouped under full group paths (blank = ungrouped). */
private fun toSections(
    profiles: List<SshProfile>,
    groups: List<ProfileGroup>,
): List<Pair<String, List<SshProfile>>> =
    profiles.groupBy { groupPath(groups, it.group) }
        .toList()
        .sortedBy { (name, _) -> name.lowercase() }
        .map { (name, ps) -> name to ps.sortedBy { it.name.lowercase() } }

/**
 * Full breadcrumb for a group (desktop resolveProfileGroupPath parity:
 * SUBGROUP header reads "GROUP -> SUBGROUP"). Blank = ungrouped.
 * Separator is ASCII `->`: desktop's `🡒` glyph may not exist in device
 * fonts (tofu risk). Depth-capped + cycle-guarded like the home tree.
 */
private fun groupPath(groups: List<ProfileGroup>, id: String?): String {
    if (id.isNullOrBlank()) return ""
    val byId = groups.associateBy { it.id }
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var cursor: String? = id
    var depth = 0
    while (cursor != null && seen.add(cursor) && depth <= 30) {
        val g = byId[cursor] ?: break
        names.add(0, g.name)
        cursor = g.parentGroupId?.takeIf { it.isNotBlank() }
        depth++
    }
    return names.joinToString(" -> ")
}

/**
 * Grouped sheet list: sticky folder header (opaque, so rows scrolling
 * underneath never bleed through) + profile rows. Ungrouped profiles
 * render without a header. Shared by the normal and searching views.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.sheetSections(
    sections: List<Pair<String, List<SshProfile>>>,
    onPick: (profileId: String) -> Unit,
) {
    for ((gname, ps) in sections) {
        if (gname.isNotBlank()) {
            stickyHeader(key = "g:$gname") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        gname,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(ps, key = { it.id }) { p ->
            SheetProfileRow(p = p, onPick = onPick)
        }
    }
}

@Composable
private fun SheetProfileRow(
    p: SshProfile,
    onPick: (profileId: String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
            .clickable { onPick(p.id) }
            .padding(vertical = 8.dp),
    ) {
        // Desktop passes the profile color per option: identity dot here.
        val dot = remember(p.color) { profileColorArgb(p.color) }
        if (dot != null) {
            Box(Modifier.size(10.dp).background(Color(dot), CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            if (p.type == "ssh") {
                Text(
                    SshDefaults.quickName(p.options.user, p.options.host, p.options.port),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "type: ${p.type} (SSH only)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
