// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.R
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
    var query by rememberSaveable { mutableStateOf("") }

    val hasAnyFiles = open.isNotEmpty() || recents.isNotEmpty() || closed.isNotEmpty()
    val terms = remember(query) { query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }

    val filteredOpen = remember(open, terms) {
        if (terms.isEmpty()) open else open.filter { tab ->
            terms.all { term -> tab.displayName.contains(term, ignoreCase = true) }
        }
    }
    val filteredRecents = remember(recents, terms) {
        if (terms.isEmpty()) recents else recents.filter { recent ->
            terms.all { term -> recent.displayName.contains(term, ignoreCase = true) }
        }
    }
    val filteredClosed = remember(closed, terms) {
        if (terms.isEmpty()) closed else closed.filter { tab ->
            terms.all { term -> tab.displayName.contains(term, ignoreCase = true) }
        }
    }
    val hasMatches = filteredOpen.isNotEmpty() || filteredRecents.isNotEmpty() || filteredClosed.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                Button(onClick = onOpen) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Text("  Open a document")
                }
            }
        }
        if (hasAnyFiles) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search open, recent and closed files") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Clear, "Clear")
                            }
                        }
                    } else null,
                    singleLine = true,
                )
            }
        }
        if (query.isNotEmpty() && !hasMatches) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No files match \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpen) {
                        Text("Search on device")
                    }
                }
            }
        } else {
            if (filteredOpen.isNotEmpty()) {
                item { SectionHeader("Open tabs") }
                items(filteredOpen, key = { "tab-" + it.id }) { tab ->
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Button) { onSelectTab(tab.id) },
                        leadingContent = { Icon(tab.kind.icon, null) },
                        headlineContent = { Text(tab.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (tab.dirty) ({ Text("Unsaved changes") }) else null,
                    )
                }
            }
            if (filteredRecents.isNotEmpty()) {
                item { SectionHeader("Recent") }
                items(filteredRecents, key = { "recent-" + it.uri }) { recent ->
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
            if (filteredClosed.isNotEmpty()) {
                item { SectionHeader("Recently closed") }
                items(filteredClosed, key = { "closed-" + it.id }) { tab ->
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
        }
        if (query.isEmpty()) {
            item { LogoFooter(showIntro = open.isEmpty() && recents.isEmpty()) }
        }
    }
}

/** The big logo at the end of Home, with a short introduction when there is nothing else yet. */
@Composable
private fun LogoFooter(showIntro: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.libreon_logo), contentDescription = null, modifier = Modifier.size(180.dp))
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (showIntro) {
                "Word, Excel, PowerPoint, PDF, Apple Pages, Numbers and Keynote, and text files open here. Everything stays on this device."
            } else {
                "No ads, no accounts, no internet. Everything stays on this device."
            },
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
