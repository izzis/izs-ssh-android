package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.SshSessionHandle
import id.web.izs.sshclient.ui.SshSessionViewModel

/**
 * Home page: SSH profiles grouped into folders per group (nested via
 * parentGroupId, desktop ProfileGroup parity). Folders expand/collapse;
 * searching switches to a flat filtered list.
 * Data comes from the Domain view (transient defaults); RAW stays lossless.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileListScreen(
    state: AppState,
    sessionViewModel: SshSessionViewModel,
    onOpen: (profileId: String) -> Unit,
    onOpenSession: (sessionId: String) -> Unit,
    onEdit: (profileId: String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showExitConfirm by remember { mutableStateOf(false) }
    // Recent section is the only collapsible one (Active stays expanded).
    var recentCollapsed by rememberSaveable { mutableStateOf(false) }
    // ConfigDisk prefs are not observable: bump to refresh the recent list
    // after a manual clear (launch-prunes already recompose via navigation).
    var recentVersion by remember { mutableStateOf(0) }
    // One migration pass per loaded store: profiles + groups stay consistent.
    val (profiles, groups) = remember(state.loaded) {
        state.displayProfiles() to state.displayGroups()
    }
    // Folders default to collapsed; only EXPANDED ids persist (disk-backed,
    // so expansion is remembered across navigation and restarts).
    var expanded by remember(state.loaded) { mutableStateOf(state.disk.expandedGroups) }

    val filtered = remember(profiles, query) {
        if (query.isBlank()) profiles
        else profiles.filter {
            it.name.contains(query, true) ||
                it.options.host.contains(query, true) ||
                it.options.user.contains(query, true)
        }
    }
    val (roots, ungrouped) = remember(profiles, groups) { buildTree(groups, profiles) }
    val rows = remember(roots, ungrouped, expanded) {
        buildList {
            for (n in roots) addNode(n, depth = 0, expanded, this)
            for (p in ungrouped.sortedBy { it.name.lowercase() }) add(HomeRow.Profile(p, 0))
        }
    }

    // Recent profiles (desktop start-page parity): N most-recently launched,
    // N = terminal.showRecentProfiles (0 hides). Ids resolve against the
    // live list, so deleted profiles prune themselves out.
    val maxRecent = remember(state.loaded) {
        RawConfigStore.showRecentProfiles(state.loaded?.store ?: emptyMap())
    }
    val byId = remember(profiles) { profiles.associateBy { it.id } }
    // Prefs read per composition (cheap): returning from a session
    // must show the just-launched profile without a reload.
    val recentIds = remember(profiles, recentVersion) {
        if (maxRecent > 0) state.disk.recentProfileIds.take(maxRecent) else emptyList()
    }
    val recent = remember(recentIds, byId) { recentIds.mapNotNull { byId[it] } }
    if (maxRecent > 0 && recentIds.size != recent.size) {
        // Prune ONLY against a fully loaded list: on a locked
        // encrypted store (or while loading) profiles are empty, and
        // pruning then would wipe recents before unlock. Desktop never
        // faces this (its store is always readable).
        if (!state.loading && state.loaded?.unlockRequired != true) {
            LaunchedEffect(recentIds, state.loaded) {
                state.disk.recentProfileIds = recent.map { it.id }
            }
        }
    }
    val liveSessions = sessionViewModel.ordered()
    // Hide Recent while searching (desktop start-page parity).
    val showRecent = maxRecent > 0 && query.isBlank() && recent.isNotEmpty()

    // Single scroll: header + Active + Recent + search + profiles share one
    // LazyColumn so a long Active/Recent list never squeezes the profile
    // viewport into a narrow strip.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "izs SSH",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text(
                    "${profiles.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = "New profile")
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
                IconButton(onClick = { showExitConfirm = true }) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Exit app")
                }
            }
        }
        if (liveSessions.isNotEmpty()) {
            item(key = "active") {
                ActiveSessionsSection(
                    sessions = liveSessions,
                    maxSessions = state.disk.maxSessions,
                    onOpenSession = onOpenSession,
                    onCloseSession = { sessionViewModel.close(it) },
                    onCloseAll = {
                        for (h in sessionViewModel.ordered()) sessionViewModel.close(h.sessionId)
                    },
                )
            }
        }
        if (showRecent) {
            item(key = "recent") {
                RecentSection(
                    recent = recent,
                    collapsed = recentCollapsed,
                    onToggle = { recentCollapsed = !recentCollapsed },
                    onClear = {
                        state.disk.recentProfileIds = emptyList()
                        recentVersion++
                    },
                    onOpen = onOpen,
                )
            }
        }
        stickyHeader(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search by name, host, or user") },
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
                singleLine = true,
            )
        }
        if (state.loading) {
            item(key = "loading") { CircularProgressIndicator() }
        }
        state.error?.let {
            item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (!state.loading && profiles.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No SSH profiles yet. Tap New profile to create one, or download one in Settings > Config Sync.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (query.isBlank()) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is HomeRow.Folder -> {
                        val id = row.node.group.id
                        val isCollapsed = id !in expanded
                        FolderRow(
                            node = row.node,
                            depth = row.depth,
                            collapsed = isCollapsed,
                            onToggle = {
                                expanded = if (isCollapsed) expanded + id else expanded - id
                                state.disk.expandedGroups = expanded
                            },
                        )
                    }
                    is HomeRow.Profile -> ProfileCard(
                        state = state,
                        profile = row.profile,
                        groupName = state.groupName(groups, row.profile.group),
                        depth = row.depth,
                        onOpen = onOpen,
                        onEdit = onEdit,
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { p ->
                ProfileCard(
                    state = state,
                    profile = p,
                    groupName = state.groupName(groups, p.group),
                    depth = 0,
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
        }
    }
    // Explicit exit: Back already goes home, so home needs its own way out.
    // Sessions are closed first (their sockets tear down off-Main), then the
    // activity finishes and the process drops with it.
    if (showExitConfirm) {
        val n = liveSessions.size
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit app?") },
            text = {
                Text(
                    if (n > 0) "$n active session${if (n > 1) "s" else ""} will be disconnected."
                    else "No active sessions.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showExitConfirm = false; onExit() },
                ) { Text("Exit", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private data class GroupNode(
    val group: ProfileGroup,
    val profiles: List<SshProfile>,
    val children: List<GroupNode>,
) {
    val totalProfiles: Int get() = profiles.size + children.sumOf { it.totalProfiles }
}

private sealed interface HomeRow {
    val key: String
    data class Folder(val node: GroupNode, val depth: Int) : HomeRow {
        override val key: String get() = "g:${node.group.id}"
    }
    data class Profile(val profile: SshProfile, val depth: Int) : HomeRow {
        override val key: String get() = "p:${profile.id}"
    }
}

/** Forest of top-level folders + ungrouped profiles. Unknown/blank/cyclic parents fall back to root. */
private fun buildTree(
    groups: List<ProfileGroup>,
    profiles: List<SshProfile>,
): Pair<List<GroupNode>, List<SshProfile>> {
    val byId = groups.associateBy { it.id }
    val children = mutableMapOf<String, MutableList<ProfileGroup>>()
    val roots = mutableListOf<ProfileGroup>()
    for (g in groups) {
        val parent = g.parentGroupId?.takeIf { it.isNotBlank() && it != g.id && byId.containsKey(it) }
        if (parent == null) roots.add(g)
        else children.getOrPut(parent) { mutableListOf() }.add(g)
    }
    val byGroup = profiles.groupBy { it.group }
    fun node(g: ProfileGroup, seen: Set<String>): GroupNode {
        val kids = if (g.id in seen) emptyList()
        else (children[g.id] ?: emptyList()).map { node(it, seen + g.id) }
        return GroupNode(
            group = g,
            profiles = (byGroup[g.id] ?: emptyList()).sortedBy { it.name.lowercase() },
            children = kids.sortedBy { it.group.name.lowercase() },
        )
    }
    val byName = Comparator<ProfileGroup> { a, b -> a.name.lowercase().compareTo(b.name.lowercase()) }
    return roots.sortedWith(byName).map { node(it, emptySet()) } to
        profiles.filter { it.group.isNullOrBlank() || !byId.containsKey(it.group) }
}

private fun addNode(n: GroupNode, depth: Int, expanded: Set<String>, out: MutableList<HomeRow>) {
    out += HomeRow.Folder(n, depth)
    if (n.group.id !in expanded) return
    for (c in n.children) addNode(c, depth + 1, expanded, out)
    for (p in n.profiles) out += HomeRow.Profile(p, depth + 1)
}

/**
 * Multi-session entry point (v1): live sessions above search, Tabby-Android
 * style. Green dot = connected, amber = connecting, red = disconnected
 * (network loss / background kill kept for reconnect). Tap re-attaches
 * without opening a duplicate; x closes the tab and frees the cap slot.
 */
@Composable
private fun ActiveSessionsSection(
    sessions: List<SshSessionHandle>,
    maxSessions: Int,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onCloseAll: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Active sessions (${sessions.size}/$maxSessions)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCloseAll) {
                    Text("Close all")
                }
            }
            for (h in sessions) {
                val status by h.status.collectAsState()
                val dot = when (status) {
                    "connected" -> Color(0xFF4CAF50)
                    "connecting…" -> Color(0xFFFFC107)
                    else -> MaterialTheme.colorScheme.error
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSession(h.sessionId) },
                ) {
                    Box(Modifier.size(12.dp).background(dot, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(h.profileSnapshot.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            SshDefaults.quickName(
                                h.profileSnapshot.options.user,
                                h.profileSnapshot.options.host,
                                h.profileSnapshot.options.port,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onCloseSession(h.sessionId) }) {
                        Text("✕", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSection(
    recent: List<SshProfile>,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onOpen: (String) -> Unit,
) {
    // Section header lives OUTSIDE the card (a wide empty card around a
    // single header row looks broken); the card wraps rows only.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        ) {
            Text(
                "Recent (${recent.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text("Clear")
            }
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!collapsed) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (p in recent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(p.id) },
                        ) {
                            // Desktop parity (profiles.service.ts): recent entries
                            // carry a history icon per row (fa-history).
                            Icon(
                                Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (p.type == "ssh") {
                                    Text(
                                        SshDefaults.quickName(
                                            p.options.user,
                                            p.options.host,
                                            p.options.port,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun FolderRow(
    node: GroupNode,
    depth: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clickable { onToggle() },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                node.group.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${node.totalProfiles}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
            )
        }
    }
}

@Composable
private fun ProfileCard(
    state: AppState,
    profile: SshProfile,
    groupName: String,
    depth: Int,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp)
            .clickable { onOpen(profile.id) },
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Identity stripe (desktop tab-colorbar parity). Absent without
            // a stored color, so uncolored rows look exactly as before.
            val stripe = remember(profile.color) { profileColorArgb(profile.color) }
            if (stripe != null) {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 52.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(stripe)),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (profile.type == "ssh") {
                    Text(
                        SshDefaults.quickName(profile.options.user, profile.options.host, profile.options.port),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    // Other types are shown for reference. Only SSH connects on this device.
                    Text(
                        "Type: ${profile.type}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (groupName.isNotBlank()) {
                    Text(groupName, style = MaterialTheme.typography.bodySmall)
                }
                if (profile.type == "ssh") {
                    val creds = buildList {
                        if (state.passwordFor(profile) != null) add("Password saved")
                        val n = state.keysFor(profile).size
                        if (n == 1) add("1 key") else if (n > 1) add("$n keys")
                    }.joinToString(", ").ifBlank { "No saved credentials" }
                    Text(
                        "Auth: ${profile.options.auth ?: "Auto"}, $creds",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Mobile v1 edits SSH profiles only; other types stay desktop-managed.
            if (profile.type == "ssh") {
                IconButton(onClick = { onEdit(profile.id) }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit profile")
                }
            }
        }
    }
}
