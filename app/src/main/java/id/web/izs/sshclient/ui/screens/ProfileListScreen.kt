package id.web.izs.sshclient.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.ProfileGroup
import id.web.izs.sshclient.core.config.SshDefaults
import id.web.izs.sshclient.core.config.SshProfile
import id.web.izs.sshclient.ui.AppState

/**
 * Home page: SSH profiles grouped into folders per group (nested via
 * parentGroupId, desktop ProfileGroup parity). Folders expand/collapse;
 * searching switches to a flat filtered list.
 * Data comes from the Domain view (transient defaults); RAW stays lossless.
 */
@Composable
fun ProfileListScreen(
    state: AppState,
    onOpen: (profileId: String) -> Unit,
    onEdit: (profileId: String) -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Profiles (${profiles.size})",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search name / host / user") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.loading && profiles.isEmpty()) {
            Text(
                "No SSH profiles yet. Open Settings > Config Sync to download a cloud config.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                if (profile.type == "ssh") {
                    Text(
                        SshDefaults.quickName(profile.options.user, profile.options.host, profile.options.port),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    // Desktop lists every type; only SSH connects on mobile.
                    Text(
                        "type: ${profile.type}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (groupName.isNotBlank()) {
                    Text(groupName, style = MaterialTheme.typography.bodySmall)
                }
                if (profile.type == "ssh") {
                    val creds = buildList {
                        if (state.passwordFor(profile) != null) add("password")
                        val n = state.keysFor(profile).size
                        if (n > 0) add("$n key")
                    }.joinToString(" + ").ifBlank { "no saved credentials" }
                    Text(
                        "auth: ${profile.options.auth ?: "?"} · $creds",
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
