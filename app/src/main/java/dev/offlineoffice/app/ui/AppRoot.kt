// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.R
import dev.offlineoffice.app.data.TabEntity
import dev.offlineoffice.app.session.DocumentTools
import dev.offlineoffice.app.session.SaveAdvice
import dev.offlineoffice.app.tabs.SaveResult
import dev.offlineoffice.app.tabs.SessionState
import dev.offlineoffice.app.tabs.TabManager
import dev.offlineoffice.app.ui.viewers.DocumentHost
import dev.offlineoffice.app.ui.viewers.pdf.SaveAsNewDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    tabs: TabManager,
    onOpen: () -> Unit,
    onSaveAs: (tabId: String, suggestedName: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val tabList by tabs.tabs.collectAsState()
    val activeId by tabs.activeId.collectAsState()
    val sessions by tabs.sessions.collectAsState()
    val restored by tabs.restored.collectAsState()
    val scope = rememberCoroutineScope()

    var showHome by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf<TabEntity?>(null) }
    var conflictTab by remember { mutableStateOf<TabEntity?>(null) }
    var saveAsNewTab by remember { mutableStateOf<TabEntity?>(null) }
    var wordCount by remember { mutableStateOf<WordCountRequest?>(null) }

    // A document opened (or switched to) while Home is showing comes to the front.
    LaunchedEffect(activeId) { showHome = false }
    val active = tabList.firstOrNull { it.id == activeId }?.takeUnless { showHome }

    fun select(id: String) {
        showHome = false
        tabs.activate(id)
    }

    fun handleSave(tab: TabEntity, result: SaveResult) = when (result) {
        SaveResult.Saved -> onMessage("Saved ${tab.displayName}")
        SaveResult.NeedsSaveAs -> onSaveAs(tab.id, tab.displayName)
        SaveResult.Conflict -> conflictTab = tab
        is SaveResult.Failed -> onMessage("Save failed: ${result.message}")
    }

    fun saveAdvice(tab: TabEntity) = ((sessions[tab.id] as? SessionState.Ready)?.session as? SaveAdvice)
        ?.takeIf { it.prefersSaveAsNew }

    fun save(tab: TabEntity) {
        if (saveAdvice(tab) != null) saveAsNewTab = tab else scope.launch { handleSave(tab, tabs.save(tab.id)) }
    }

    fun tools(tab: TabEntity) = (sessions[tab.id] as? SessionState.Ready)?.session as? DocumentTools

    fun requestClose(tab: TabEntity) {
        if (tab.dirty) confirmClose = tab else tabs.close(tab.id)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                active?.displayName ?: stringResource(R.string.app_name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (active?.dirty == true) Badge(Modifier.padding(start = 6.dp))
                        }
                    },
                    navigationIcon = {
                        if (active != null) {
                            IconButton(onClick = { showHome = true }) { Icon(Icons.Outlined.Home, "Home") }
                        }
                    },
                    actions = {
                        if (active != null) {
                            IconButton(onClick = { tools(active)?.findRequests?.tryEmit(Unit) }, enabled = tools(active) != null) {
                                Icon(Icons.Outlined.Search, "Find in document")
                            }
                            IconButton(onClick = { save(active) }, enabled = active.dirty) { Icon(Icons.Outlined.Save, "Save") }
                        }
                        TabCountChip(count = tabList.size, onClick = { showSwitcher = true })
                        OverflowMenu(
                            hasActive = active != null,
                            onOpen = onOpen,
                            onSaveAs = { active?.let { onSaveAs(it.id, it.displayName) } },
                            onClose = { active?.let(::requestClose) },
                            onWordCount = { active?.let { tab -> tools(tab)?.let { wordCount = WordCountRequest(tab.displayName, it) } } },
                            onLicenses = { showLicenses = true },
                        )
                    },
                )
                BoxWithConstraints {
                    if (maxWidth >= 600.dp && tabList.isNotEmpty()) {
                        val index = tabList.indexOfFirst { it.id == active?.id }
                        PrimaryScrollableTabRow(selectedTabIndex = index.coerceAtLeast(0), edgePadding = 8.dp) {
                            tabList.forEach { tab ->
                                Tab(
                                    selected = tab.id == active?.id,
                                    onClick = { select(tab.id) },
                                    text = { Text(tab.displayName + if (tab.dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    icon = { Icon(tab.kind.icon, null, Modifier.size(18.dp)) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (active == null) {
                HomeScreen(tabs = tabs, onOpen = onOpen, onSelectTab = ::select, onMessage = onMessage)
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (active.id in restored) {
                        RestoredBanner(
                            onKeep = { tabs.dismissRestored(active.id) },
                            onDiscard = { tabs.discardChanges(active.id) },
                        )
                    }
                    when (val state = sessions[active.id]) {
                        is SessionState.Ready -> DocumentHost(state.session, onSave = { if (active.dirty) save(active) }, onReload = { tabs.reload(active.id) })
                        is SessionState.Failed -> CenteredMessage("Could not open ${active.displayName}\n${state.message}")
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    if (showSwitcher) {
        TabSwitcherSheet(
            tabs = tabList,
            activeId = active?.id,
            onSelect = { select(it); showSwitcher = false },
            onClose = ::requestClose,
            onMove = tabs::move,
            onDismiss = { showSwitcher = false },
            onOpen = { showSwitcher = false; onOpen() },
        )
    }
    confirmClose?.let { tab ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("Save changes to ${tab.displayName}?") },
            text = { Text("Your changes will be lost if you don't save them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = null
                    if (saveAdvice(tab) != null) {
                        saveAsNewTab = tab
                    } else {
                        scope.launch {
                            val result = tabs.save(tab.id)
                            if (result == SaveResult.Saved) tabs.close(tab.id) else handleSave(tab, result)
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = null; tabs.close(tab.id) }) { Text("Don't save") }
                    TextButton(onClick = { confirmClose = null }) { Text("Cancel") }
                }
            },
        )
    }
    conflictTab?.let { tab ->
        AlertDialog(
            onDismissRequest = { conflictTab = null },
            title = { Text("File changed elsewhere") },
            text = { Text("${tab.displayName} was modified by another app since you opened it.") },
            confirmButton = {
                TextButton(onClick = {
                    conflictTab = null
                    scope.launch { handleSave(tab, tabs.overwrite(tab.id)) }
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { conflictTab = null; onSaveAs(tab.id, tab.displayName) }) { Text("Save as copy") }
            },
        )
    }
    saveAsNewTab?.let { tab ->
        val suggested = saveAdvice(tab)?.suggestedSaveName(tab.displayName) ?: tab.displayName
        SaveAsNewDialog(
            displayName = tab.displayName,
            suggestedName = suggested,
            onSaveAsNew = { saveAsNewTab = null; onSaveAs(tab.id, suggested) },
            onOverwrite = { saveAsNewTab = null; scope.launch { handleSave(tab, tabs.save(tab.id)) } },
            onDismiss = { saveAsNewTab = null },
        )
    }
    wordCount?.let { WordCountDialog(it, onDismiss = { wordCount = null }) }
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun TabCountChip(count: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text("$count", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OverflowMenu(
    hasActive: Boolean,
    onOpen: () -> Unit,
    onSaveAs: () -> Unit,
    onClose: () -> Unit,
    onWordCount: () -> Unit,
    onLicenses: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.MoreVert, "More") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Open…") },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                onClick = { expanded = false; onOpen() },
            )
            if (hasActive) {
                DropdownMenuItem(text = { Text("Save as…") }, onClick = { expanded = false; onSaveAs() })
                DropdownMenuItem(text = { Text("Word count") }, onClick = { expanded = false; onWordCount() })
                DropdownMenuItem(
                    text = { Text("Close tab") },
                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                    onClick = { expanded = false; onClose() },
                )
            }
            DropdownMenuItem(text = { Text("Open-source licenses") }, onClick = { expanded = false; onLicenses() })
        }
    }
}

@Composable
private fun RestoredBanner(onKeep: () -> Unit, onDiscard: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Unsaved changes were restored.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDiscard) { Text("Discard") }
            TextButton(onClick = onKeep) { Text("Keep") }
        }
    }
}

@Composable
fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
