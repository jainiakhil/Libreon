// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.tabs.OpenOrigin
import dev.offlineoffice.app.tabs.TabManager
import kotlinx.coroutines.launch

/** Landing screen: open a file, jump back into open tabs, recents and recently closed. */
@Composable
fun HomeScreen(tabs: TabManager, onOpen: () -> Unit, onSelectTab: (String) -> Unit, onMessage: (String) -> Unit) {
    val open by tabs.tabs.collectAsState()
    val recents by tabs.recents.collectAsState(emptyList())
    val closed by tabs.closedTabs.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onOpen) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Text("  Open a document")
                }
            }
        }
        if (open.isNotEmpty()) {
            item { SectionHeader("Open tabs") }
            items(open, key = { "tab-" + it.id }) { tab ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) { onSelectTab(tab.id) },
                    leadingContent = { Icon(tab.kind.icon, null) },
                    headlineContent = { Text(tab.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (tab.dirty) ({ Text("Unsaved changes") }) else null,
                )
            }
        }
        if (recents.isNotEmpty()) {
            item { SectionHeader("Recent") }
            items(recents, key = { "recent-" + it.uri }) { recent ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) {
                        if (!recent.reopenable) {
                            onMessage("Pick ${recent.displayName} again with Open to reopen it")
                            return@clickable
                        }
                        scope.launch {
                            runCatching { tabs.open(Uri.parse(recent.uri), OpenOrigin.PICKER, recent.mimeType) }
                                .onFailure { onMessage("Cannot reopen ${recent.displayName}: it may have moved or been deleted") }
                        }
                    },
                    leadingContent = { Icon(recent.kind.icon, null) },
                    headlineContent = { Text(recent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val size = recent.size?.let { Formatter.formatShortFileSize(context, it) + " · " }.orEmpty()
                        val time = DateUtils.getRelativeTimeSpanString(recent.lastOpenedAt)
                        Text(size + time + if (recent.reopenable) "" else " · re-pick to open")
                    },
                    trailingContent = if (recent.pinned) ({ Icon(Icons.Outlined.PushPin, "Pinned") }) else null,
                )
            }
        }
        if (closed.isNotEmpty()) {
            item { SectionHeader("Recently closed") }
            items(closed, key = { "closed-" + it.id }) { tab ->
                ListItem(
                    modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch { if (!tabs.reopen(tab)) onMessage("${tab.displayName} can no longer be opened without picking it again") }
                    },
                    leadingContent = { Icon(tab.kind.icon, null) },
                    headlineContent = { Text(tab.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Closed " + DateUtils.getRelativeTimeSpanString(tab.closedAt ?: 0)) },
                )
            }
        }
        if (open.isEmpty() && recents.isEmpty()) {
            item {
                Text(
                    "Word, Excel, PowerPoint, PDF and text files open here. Everything stays on this device.",
                    Modifier.fillMaxWidth().padding(32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
