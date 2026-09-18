package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.RawConfigStore
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.core.config.profileColorArgb
import id.web.izs.sshclient.ui.AppState
import id.web.izs.sshclient.ui.SshSessionHandle
import id.web.izs.sshclient.ui.SshSessionViewModel
import kotlinx.coroutines.launch

/**
 * Home page: SSH profiles grouped into folders per group (nested via
 * parentGroupId, desktop ProfileGroup parity). Folders expand/collapse;
 * searching keeps the grouped view over the filtered matches.
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
    // Group manage (pencil): rename + reparent inline, delete with
    // confirm. Node snapshot is fine — the dialog closes on every
    // successful write.
    var manageNode by remember { mutableStateOf<GroupNode?>(null) }
    var confirmDeleteNode by remember { mutableStateOf<GroupNode?>(null) }
    var renameText by remember { mutableStateOf("") }
    var parentId by remember { mutableStateOf<String?>(null) }
    var groupBusy by remember { mutableStateOf(false) }
    var groupMsg by remember { mutableStateOf<String?>(null) }
    var showUnlock by remember { mutableStateOf(false) }
    var pendingGroupRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()

    fun openManage(node: GroupNode) {
        manageNode = node
        renameText = node.group.name
        parentId = node.group.parentGroupId?.takeIf { it.isNotBlank() }
        groupMsg = null
    }

    // Eligible parents: every group except self + descendants (cycle
    // guard; the raw helper re-checks as backstop).
    val eligibleParents = remember(manageNode, groups) {
        val node = manageNode ?: return@remember emptyList<ProfileGroup>()
        val kids = mutableMapOf<String, MutableList<String>>()
        for (g in groups) {
            g.parentGroupId?.takeIf { it.isNotBlank() }?.let {
                kids.getOrPut(it) { mutableListOf() } += g.id
            }
        }
        val banned = mutableSetOf(node.group.id)
        val stack = ArrayDeque(listOf(node.group.id))
        while (stack.isNotEmpty()) {
            for (k in kids[stack.removeFirst()].orEmpty()) {
                if (banned.add(k)) stack.add(k)
            }
        }
        groups.filter { it.id !in banned }.sortedBy { it.name.lowercase() }
    }

    fun doSaveGroup() {
        val node = manageNode ?: return
        val name = renameText.trim()
        if (name.isBlank()) {
            groupMsg = "Group name is empty"
            return
        }
        scope.launch {
            groupBusy = true
            groupMsg = null
            try {
                if (name != node.group.name) state.repo.renameGroup(node.group.id, name)
                val origParent = node.group.parentGroupId?.takeIf { it.isNotBlank() }
                if (parentId != origParent) state.repo.moveGroup(node.group.id, parentId)
                state.refresh {}
                manageNode = null
            } catch (e: IllegalStateException) {
                // Encrypted shell: rewriting the blob needs the passphrase
                // (lazy-unlock parity with the profile editor).
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingGroupRetry = { doSaveGroup() }
                    showUnlock = true
                } else {
                    groupMsg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                groupMsg = "Couldn't save: ${e.message}"
            } finally {
                groupBusy = false
            }
        }
    }

    fun doDeleteGroup() {
        val node = confirmDeleteNode ?: return
        scope.launch {
            groupBusy = true
            groupMsg = null
            try {
                state.repo.deleteGroup(node.group.id)
                expanded = expanded - node.group.id
                state.disk.expandedGroups = expanded
                state.refresh {}
                confirmDeleteNode = null
                manageNode = null
            } catch (e: IllegalStateException) {
                if ((e.message ?: "").contains("locked", ignoreCase = true)) {
                    pendingGroupRetry = { doDeleteGroup() }
                    showUnlock = true
                } else {
                    groupMsg = "Couldn't save: ${e.message}"
                }
            } catch (e: Exception) {
                groupMsg = "Couldn't save: ${e.message}"
            } finally {
                groupBusy = false
            }
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
    val (roots, ungrouped) = remember(profiles, groups) { buildTree(groups, profiles) }
    // Per top-level folder: flattened child rows (subfolders + profiles),
    // empty when collapsed (only the sticky folder bar stays visible).
    // While searching the same shape is built over the filtered matches
    // with everything force-expanded, so matches are never hidden inside
    // a collapsed folder; groups without matches are skipped at render.
    val sections = remember(roots, expanded) { buildSections(roots, expanded, forceExpand = false) }
    val ungroupedSorted = remember(ungrouped) { ungrouped.sortedBy { it.name.lowercase() } }
    val (fRoots, fUngrouped) = remember(groups, filtered) { buildTree(groups, filtered) }
    val fSections = remember(fRoots, expanded) { buildSections(fRoots, expanded, forceExpand = true) }
    val fUngroupedSorted = remember(fUngrouped) { fUngrouped.sortedBy { it.name.lowercase() } }

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

    // Fixed top bar + fixed search above the scrollable list; Active +
    // Recent + profiles share one LazyColumn below, so a long
    // Active/Recent list never squeezes the profile viewport into a
    // narrow strip. Folder bars are native sticky headers: exactly one
    // stays pinned and the next folder pushes it off, smooth and
    // scroll-driven by the framework itself. No mirrored copy anywhere,
    // so doubling is impossible; no fake backgrounds, so no black bars.
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
        // Fixed search: outside the list, so it never competes with (or
        // gets displaced by) the folder sticky headers below.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search by name, host, or user") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            groupSections(
                sections = sections,
                expanded = expanded,
                forceExpand = false,
                state = state,
                onToggle = { id, isCollapsed ->
                    expanded = if (isCollapsed) expanded + id else expanded - id
                    state.disk.expandedGroups = expanded
                },
                onOpen = onOpen,
                onEdit = onEdit,
                onManageGroup = { openManage(it) },
            )
            items(ungroupedSorted, key = { it.id }) { p ->
                ProfileCard(
                    state = state,
                    profile = p,
                    depth = 0,
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
        } else {
            groupSections(
                sections = fSections.filter { it.second.isNotEmpty() },
                expanded = expanded,
                forceExpand = true,
                state = state,
                onToggle = { id, isCollapsed ->
                    expanded = if (isCollapsed) expanded + id else expanded - id
                    state.disk.expandedGroups = expanded
                },
                onOpen = onOpen,
                onEdit = onEdit,
                onManageGroup = { openManage(it) },
            )
            items(fUngroupedSorted, key = { it.id }) { p ->
                ProfileCard(
                    state = state,
                    profile = p,
                    depth = 0,
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
            if (fSections.all { it.second.isEmpty() } && fUngroupedSorted.isEmpty()) {
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
    // Group manage (folder pencil): rename inline, delete behind an explicit
    // confirm that states the ungroup outcome (deleteProfiles:false parity).
    manageNode?.let { node ->
        AlertDialog(
            onDismissRequest = { if (!groupBusy) manageNode = null },
            title = { Text("Manage group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Group name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    ParentGroupDropdown(
                        parents = eligibleParents,
                        selected = parentId,
                        onSelect = { parentId = it },
                    )
                    groupMsg?.let { m ->
                        Text(m, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = { confirmDeleteNode = node },
                        enabled = !groupBusy,
                    ) {
                        Text("Delete group", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doSaveGroup() }, enabled = !groupBusy) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { manageNode = null }, enabled = !groupBusy) {
                    Text("Cancel")
                }
            },
        )
    }
    confirmDeleteNode?.let { node ->
        val members = node.totalProfiles
        val kids = node.children.size
        AlertDialog(
            onDismissRequest = { if (!groupBusy) confirmDeleteNode = null },
            title = { Text("Delete group \"${node.group.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (members == 0) "The group is empty."
                        else "$members profile${if (members > 1) "s" else ""} become ungrouped.",
                    )
                    if (kids > 0) {
                        Text("$kids sub-group${if (kids > 1) "s" else ""} move to top level.")
                    }
                    groupMsg?.let { m ->
                        Text(m, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { doDeleteGroup() }, enabled = !groupBusy) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteNode = null }, enabled = !groupBusy) {
                    Text("Cancel")
                }
            },
        )
    }
    if (showUnlock) {
        VaultUnlockDialog(
            state = state,
            onUnlocked = {
                showUnlock = false
                pendingGroupRetry?.invoke()
                pendingGroupRetry = null
            },
            onNoConfig = { showUnlock = false },
            onDismiss = { showUnlock = false; pendingGroupRetry = null },
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
        // Alphabetical at every level (desktop display parity: the
        // selector sorts by group-path/name, so the visible order is
        // alphabetical even though the raw tree keeps config order).
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

private fun addNode(
    n: GroupNode,
    depth: Int,
    expanded: Set<String>,
    out: MutableList<HomeRow>,
    forceExpand: Boolean = false,
) {
    out += HomeRow.Folder(n, depth)
    if (!forceExpand && n.group.id !in expanded) return
    // Desktop profilesSettingsTab parity: profiles first, child folders
    // at the bottom (their template renders group.profiles, then
    // group.children — never the reverse).
    for (p in n.profiles) out += HomeRow.Profile(p, depth + 1)
    for (c in n.children) addNode(c, depth + 1, expanded, out, forceExpand)
}

/** Flattened child rows per top-level folder (subfolders + profiles). */
private fun buildSections(
    roots: List<GroupNode>,
    expanded: Set<String>,
    forceExpand: Boolean,
): List<Pair<GroupNode, List<HomeRow>>> =
    roots.map { root ->
        root to buildList {
            if (forceExpand || root.group.id in expanded) {
                for (p in root.profiles) add(HomeRow.Profile(p, 1))
                for (c in root.children) addNode(c, depth = 1, expanded, this, forceExpand)
            }
        }
    }

/**
 * Grouped profile list: native sticky folder header (exactly one stays
 * pinned; the next folder pushes it off, tappable while pinned) + child
 * rows. Shared by the normal and the searching view.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.groupSections(
    sections: List<Pair<GroupNode, List<HomeRow>>>,
    expanded: Set<String>,
    forceExpand: Boolean,
    state: AppState,
    onToggle: (id: String, isCollapsed: Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onManageGroup: (GroupNode) -> Unit,
) {
    for ((root, sub) in sections) {
        val id = root.group.id
        val isCollapsed = !forceExpand && id !in expanded
        stickyHeader(key = "g:$id") {
            FolderRow(
                node = root,
                depth = 0,
                collapsed = isCollapsed,
                onToggle = { onToggle(id, isCollapsed) },
                onManage = { onManageGroup(root) },
            )
        }
        items(sub, key = { it.key }) { row ->
            when (row) {
                is HomeRow.Folder -> {
                    val cid = row.node.group.id
                    val cCollapsed = !forceExpand && cid !in expanded
                    FolderRow(
                        node = row.node,
                        depth = row.depth,
                        collapsed = cCollapsed,
                        onToggle = { onToggle(cid, cCollapsed) },
                        onManage = { onManageGroup(row.node) },
                    )
                }
                is HomeRow.Profile -> ProfileCard(
                    state = state,
                    profile = row.profile,
                    depth = row.depth,
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
        }
    }
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
    onManage: () -> Unit,
) {
    // Slim sticky-header bar, deliberately NOT a Card: profile rows are
    // multi-line Cards, folders are single-line tonal headers (desktop
    // settings-tree parity). Open/closed reads from Folder/FolderOpen.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onToggle,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                node.group.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${node.totalProfiles}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Desktop parity: folder actions are hover-revealed there (no
            // hover on touch), so the pencil stays low-emphasis instead of
            // a full primary-colour button.
            IconButton(onClick = onManage, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Rename or delete group",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Parent picker for the Manage-group dialog (GroupDropdown parity in the profile editor). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentGroupDropdown(
    parents: List<ProfileGroup>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        selected.isNullOrBlank() -> "Top level"
        else -> parents.find { it.id == selected }?.name ?: "Top level"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label, onValueChange = { },
            readOnly = true, label = { Text("Parent group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Top level") },
                onClick = { onSelect(null); expanded = false },
            )
            for (g in parents) {
                DropdownMenuItem(
                    text = { Text(g.name) },
                    onClick = { onSelect(g.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(
    state: AppState,
    profile: SshProfile,
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
                if (profile.type == "ssh") {
                    // Locked vault hides secrets (passwordFor/keysFor resolve
                    // to null while locked), so say so instead of claiming
                    // nothing is saved — the creds may be inside the vault.
                    val locked = state.loaded?.needsPassphrase == true
                    val creds = if (locked) "Locked — unlock to view"
                    else buildList {
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
                    // Low-emphasis like the folder pencil (desktop
                    // hover-action parity): solid black is too harsh,
                    // especially in the light theme.
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit profile",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
