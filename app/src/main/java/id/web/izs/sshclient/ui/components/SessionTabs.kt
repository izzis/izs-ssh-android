package id.web.izs.sshclient.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.web.izs.sshclient.core.config.TabLocation
import id.web.izs.sshclient.ui.SshSessionHandle

/**
 * Tab chrome over the multi-session registry (desktop tab-header parity).
 * Two presentations, one item: a horizontal [SessionTabStrip] (tabsLocation
 * top/bottom) and a vertical drawer list ([SessionTabDrawerContent] for
 * left/right). Titles come from [titleOf] so the caller resolves live
 * profile names (deleted-profile fallback included); the component only
 * observes handle status/activity flows.
 *
 * Close confirmation (warnOnClose) lives with the caller: [onCloseRequest]
 * fires, the caller confirms, then calls `viewModel.close()`.
 */

fun statusDotColor(status: String): Color = when (status) {
    "connected" -> Color(0xFF4CAF50)
    "connecting…" -> Color(0xFFFFC107)
    else -> Color(0xFFB00020)
}

@Composable
private fun CloseBtn(sessionId: String, onCloseRequest: (String) -> Unit) {
    IconButton(
        onClick = { onCloseRequest(sessionId) },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(16.dp))
    }
}

/**
 * One tab: status dot + title + close, with a desktop-style activity
 * underline. Shared by strip (fixed max width, ellipsis) and drawer (full
 * width). Selected state is a container tint; background output on a
 * background tab draws a primary line under the tab (cleared on select).
 */
@Composable
fun SessionTabItem(
    handle: SshSessionHandle,
    title: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    onCloseRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) {
    val status by handle.status.collectAsState()
    val activity by handle.activity.collectAsState()
    Column(
        modifier = modifier
            .then(if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent,
                )
                .clickable { onSelect(handle.sessionId) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Box(Modifier.size(10.dp).background(statusDotColor(status), CircleShape))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
                modifier = if (fillMaxWidth) Modifier.weight(1f) else Modifier.widthIn(max = 140.dp),
            )
            CloseBtn(handle.sessionId, onCloseRequest)
        }
        // Desktop-style activity marker: a primary underline, hidden on the
        // selected tab (select() already clears the flag). Primary, not
        // tertiary: the theme leaves tertiary at the M3 baseline pinkish
        // default, which reads as an error red next to the status dots.
        if (activity && !selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 2.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Horizontal strip for tabsLocation top/bottom. Auto-scrolls to selected.
 * [scroll] is caller-owned (hoisted): every tab is its own navigation
 * destination, so a strip-local scroll state would reset to the left edge
 * on every tab switch — the caller seeds it from the shared position.
 */
@Composable
fun SessionTabStrip(
    sessions: List<SshSessionHandle>,
    selectedId: String,
    titleOf: (SshSessionHandle) -> String,
    onSelect: (String) -> Unit,
    onCloseRequest: (String) -> Unit,
    onNew: () -> Unit,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            for (h in sessions) {
                key(h.sessionId) {
                    val bring = remember { BringIntoViewRequester() }
                    SessionTabItem(
                        handle = h,
                        title = titleOf(h),
                        selected = h.sessionId == selectedId,
                        onSelect = onSelect,
                        onCloseRequest = onCloseRequest,
                        modifier = Modifier.bringIntoViewRequester(bring),
                    )
                    if (h.sessionId == selectedId) {
                        LaunchedEffect(h.sessionId) {
                            try { bring.bringIntoView() } catch (_: Exception) { }
                        }
                    }
                }
            }
            IconButton(onClick = onNew, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
        }
    }
}

/** Vertical list for the left/right drawer sheet. */
@Composable
fun SessionTabDrawerContent(
    sessions: List<SshSessionHandle>,
    selectedId: String,
    titleOf: (SshSessionHandle) -> String,
    onSelect: (String) -> Unit,
    onCloseRequest: (String) -> Unit,
    onNew: () -> Unit,
    onHome: () -> Unit = {},
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Tabs (${sessions.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            for (h in sessions) {
                key(h.sessionId) {
                    SessionTabItem(
                        handle = h,
                        title = titleOf(h),
                        selected = h.sessionId == selectedId,
                        onSelect = onSelect,
                        onCloseRequest = onCloseRequest,
                        fillMaxWidth = true,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNew() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("New connection…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Pinned footer: drawer mode replaces the header back arrow with a
        // hamburger, and sheet new-tab mode no longer navigates home — so
        // the drawer is the only visible way back to the profile list.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onHome() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Profile list", style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSettings() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Settings", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Custom side tab drawer for tabsLocation left/right. Deliberately NOT M3
 * ModalNavigationDrawer: that sheet is start-side only, and mirroring the
 * whole screen RTL to move it mirrors the terminal UI too (everything
 * right-aligned). This frame keeps the screen LTR and slides a plain sheet
 * from the requested side. Other [side] values render [content] directly.
 * Open/close state is caller-owned (hamburger, scrim tap, Back, swipe).
 */
@Composable
fun TabDrawerFrame(
    side: TabLocation,
    open: Boolean,
    onClose: () -> Unit,
    drawer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (side != TabLocation.LEFT && side != TabLocation.RIGHT) {
        content()
        return
    }
    val fromLeft = side == TabLocation.LEFT
    Box(modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { w -> if (fromLeft) -w else w } + fadeIn(),
            exit = slideOutHorizontally { w -> if (fromLeft) -w else w } + fadeOut(),
            modifier = Modifier.align(
                if (fromLeft) Alignment.CenterStart else Alignment.CenterEnd,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(300.dp),
                shape = RoundedCornerShape(
                    topEnd = if (fromLeft) 16.dp else 0.dp,
                    bottomEnd = if (fromLeft) 16.dp else 0.dp,
                    topStart = if (fromLeft) 0.dp else 16.dp,
                    bottomStart = if (fromLeft) 0.dp else 16.dp,
                ),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                drawer()
            }
        }
    }
}
