package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.ui.AppState

/**
 * Quick-pick sheet for the terminal "+" button (Settings > Window >
 * New tab = sheet). Search + recent + compact profile list over the
 * current session — no navigation away from the terminal.
 *
 * Windowing lives in [AnchoredSheet] (shared with the SFTP browser):
 * the header below is the drag-handle zone, the list scrolls first and
 * only edge leftover moves the sheet half <-> full <-> hidden.
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
    // Hidden profiles never appear in the picker — not even in search
    // (desktop selector parity: it filters profileBlacklist first).
    val profiles = remember(state.loaded) {
        val hidden = RawConfigStore.profileBlacklistOf(state.loaded?.store ?: emptyMap())
        if (hidden.isEmpty()) state.displayProfiles()
        else state.displayProfiles().filter { it.id !in hidden }
    }
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
    val filtered = remember(profiles, query, state.loaded) {
        if (query.isBlank()) profiles
        else profiles.filter {
            it.name.contains(query, true) ||
                it.options.host.contains(query, true) ||
                (!state.isAskUsername(it.id) && it.options.user.contains(query, true))
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

    AnchoredSheet(
        onDismiss = onDismiss,
        handle = {
            Text(
                "New tab",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            CompactFilterField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Filter by name, host, or user",
                showClear = query.isNotEmpty(),
                onClear = { query = "" },
                modifier = Modifier.padding(top = 8.dp),
            )
        },
    ) {
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
                                    SshDefaults.displayQuickName(
                                        p.options.user,
                                        p.options.host,
                                        p.options.port,
                                        askUsername = state.isAskUsername(p.id),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (query.isBlank()) {
                sheetSections(
                    sections = sections,
                    isAsk = { state.isAskUsername(it) },
                    onPick = onPick,
                )
            } else {
                // groupBy yields only groups with matches; an empty
                // result shows a note instead of a blank sheet.
                sheetSections(
                    sections = fSections,
                    isAsk = { state.isAskUsername(it) },
                    onPick = onPick,
                )
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
    isAsk: (profileId: String) -> Boolean,
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
            SheetProfileRow(p = p, askUsername = isAsk(p.id), onPick = onPick)
        }
    }
}

@Composable
private fun SheetProfileRow(
    p: SshProfile,
    askUsername: Boolean,
    onPick: (profileId: String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
            .clickable { onPick(p.id) }
            .padding(vertical = 8.dp),
    ) {
        // Desktop selector parity: monitor icon tinted with the profile
        // color (same treatment as the home list card).
        val dot = remember(p.color) { profileColorArgb(p.color) }
        Icon(
            Icons.Outlined.DesktopWindows,
            contentDescription = null,
            tint = if (dot != null) Color(dot) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            if (p.type == "ssh") {
                Text(
                    SshDefaults.displayQuickName(
                        p.options.user,
                        p.options.host,
                        p.options.port,
                        askUsername = askUsername,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
