// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.offlineoffice.app.R
import dev.offlineoffice.app.data.Settings
import dev.offlineoffice.app.data.TabEntity
import dev.offlineoffice.app.doc.DocumentKind
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
    settings: Settings,
    onOpen: () -> Unit,
    onSaveAs: (tabId: String, suggestedName: String) -> Unit,
    onShare: (tabId: String, displayName: String, asPdf: Boolean) -> Unit,
    onExportPdf: (tabId: String, displayName: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val tabList by tabs.tabs.collectAsState()
    val activeId by tabs.activeId.collectAsState()
    val sessions by tabs.sessions.collectAsState()
    val restored by tabs.restored.collectAsState()
    val lightTabs by settings.lightTabs.collectAsState()
    val tabBar by settings.tabBar.collectAsState()
    val dark = LocalDarkTheme.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val scope = rememberCoroutineScope()

    var showHome by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmCloseAll by remember { mutableStateOf(false) }
    var confirmClearHome by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<TabEntity?>(null) }
    var confirmClose by remember { mutableStateOf<TabEntity?>(null) }
    var conflictTab by remember { mutableStateOf<TabEntity?>(null) }
    var saveAsNewTab by remember { mutableStateOf<TabEntity?>(null) }
    var wordCount by remember { mutableStateOf<WordCountRequest?>(null) }

    // A document opened (or switched to) while Home is showing comes to the front.
    LaunchedEffect(activeId) { showHome = false }
    LaunchedEffect(tabs) { tabs.activations.collect { showHome = false } }
    val active = tabList.firstOrNull { it.id == activeId }?.takeUnless { showHome }
    LaunchedEffect(tabList) { settings.retainLightTabs(tabList.map { it.id }.toSet()) }

    // Back from a document returns to Home; back on Home leaves the app as usual.
    BackHandler(enabled = active != null) { showHome = true }

    // Full-screen reading: the bars go, and back (or the button in the corner) brings them back.
    var fullScreen by remember { mutableStateOf(false) }
    if (active == null) fullScreen = false
    BackHandler(enabled = fullScreen) { fullScreen = false }
    FullScreenEffect(fullScreen)

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
            if (!fullScreen) Column {
                TopAppBar(
                    title = {
                        if (active == null) {
                            HomeTitle()
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    active.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (active.dirty) Badge(Modifier.padding(start = 6.dp))
                            }
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
                        if (wide && tabList.isNotEmpty()) {
                            IconButton(onClick = { settings.setTabBar(!tabBar) }) {
                                Icon(
                                    if (tabBar) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    if (tabBar) "Hide tab bar" else "Show tab bar",
                                )
                            }
                        }
                        TabCountChip(count = tabList.size, onClick = { showSwitcher = true })
                        OverflowMenu(
                            hasActive = active != null,
                            onOpen = onOpen,
                            onSaveAs = { active?.let { onSaveAs(it.id, it.displayName) } },
                            onClose = { active?.let(::requestClose) },
                            onWordCount = { active?.let { tab -> tools(tab)?.let { wordCount = WordCountRequest(tab.displayName, it) } } },
                            onShare = { asPdf -> active?.let { onShare(it.id, it.displayName, asPdf) } },
                            onExportPdf = active?.takeIf { it.kind.engine == DocumentKind.Engine.OFFICE }
                                ?.let { tab -> { onExportPdf(tab.id, tab.displayName) } },
                            onFullScreen = { fullScreen = true },
                            onDiscard = active?.takeIf { it.dirty }?.let { tab -> { confirmDiscard = tab } },
                            lightPages = active?.takeIf { dark }?.let { it.id in lightTabs },
                            onLightPages = { light -> active?.let { settings.setLightPages(it.id, light) } },
                            onCloseAll = if (tabList.isNotEmpty()) ({ confirmCloseAll = true }) else null,
                            onClearHome = if (active == null) ({ confirmClearHome = true }) else null,
                            onSettings = { showSettings = true },
                            onLicenses = { showLicenses = true },
                        )
                    },
                )
                BoxWithConstraints {
                    if (maxWidth >= 600.dp && tabList.isNotEmpty() && tabBar) {
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
                        is SessionState.Ready -> {
                            // A tab can ask for light pages while the app is dark.
                            val light = dark && active.id in lightTabs
                            AppTheme(dark = dark && !light) {
                                Surface(color = MaterialTheme.colorScheme.background) {
                                    CompositionLocalProvider(LocalFullScreen provides fullScreen) {
                                        DocumentHost(state.session, onSave = { if (active.dirty) save(active) }, onReload = { tabs.reload(active.id) })
                                    }
                                }
                            }
                        }
                        is SessionState.Failed -> CenteredMessage("Could not open ${active.displayName}\n${state.message}")
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    if (fullScreen) {
        // A popup is its own window, so it stays above an Office page's surface.
        Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 0)) {
            Surface(
                Modifier.padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.6f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                IconButton(onClick = { fullScreen = false }) { Icon(Icons.Outlined.FullscreenExit, "Exit full screen") }
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
    if (showSettings) SettingsScreen(settings, onLicenses = { showLicenses = true }, onDismiss = { showSettings = false })
    confirmDiscard?.let { tab ->
        ConfirmDialog(
            title = "Discard changes to ${tab.displayName}?",
            text = "Your edits since the last save will be lost and the document reopens as it was saved.",
            confirm = "Discard",
            onConfirm = { confirmDiscard = null; tabs.discardChanges(tab.id) },
            onDismiss = { confirmDiscard = null },
        )
    }
    if (confirmCloseAll) {
        val unsaved = tabList.count { it.dirty }
        ConfirmDialog(
            title = "Close all tabs?",
            text = if (unsaved > 0) {
                "$unsaved ${if (unsaved == 1) "tab has" else "tabs have"} unsaved changes, which will be lost."
            } else {
                "All ${tabList.size} open documents will be closed. You can reopen them from Recently closed."
            },
            confirm = "Close all",
            onConfirm = { confirmCloseAll = false; showHome = false; tabs.closeAll() },
            onDismiss = { confirmCloseAll = false },
        )
    }
    if (confirmClearHome) {
        ConfirmDialog(
            title = "Clear Home?",
            text = "This closes every open tab and removes all recent and recently closed files from Home. " +
                "Unsaved changes are lost. Your files themselves are not deleted.",
            confirm = "Clear",
            onConfirm = { confirmClearHome = false; tabs.clearHome() },
            onDismiss = { confirmClearHome = false },
        )
    }
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
    onShare: (asPdf: Boolean) -> Unit,
    /** Shown for Office documents. */
    onExportPdf: (() -> Unit)?,
    onFullScreen: () -> Unit,
    /** Shown when the document has unsaved changes. */
    onDiscard: (() -> Unit)?,
    /** null hides the item (the app is light); otherwise whether this tab uses light pages. */
    lightPages: Boolean?,
    onLightPages: (Boolean) -> Unit,
    onCloseAll: (() -> Unit)?,
    onClearHome: (() -> Unit)?,
    onSettings: () -> Unit,
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
                DropdownMenuItem(
                    text = { Text("Save as…") },
                    leadingIcon = { Icon(Icons.Outlined.SaveAs, null) },
                    onClick = { expanded = false; onSaveAs() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Outlined.Share, null) },
                    onClick = { expanded = false; onShare(false) },
                )
                onExportPdf?.let { export ->
                    DropdownMenuItem(
                        text = { Text("Share as PDF") },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = { expanded = false; onShare(true) },
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF…") },
                        leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                        onClick = { expanded = false; export() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Full screen") },
                    leadingIcon = { Icon(Icons.Outlined.Fullscreen, null) },
                    onClick = { expanded = false; onFullScreen() },
                )
                DropdownMenuItem(
                    text = { Text("Word count") },
                    leadingIcon = { Icon(Icons.Outlined.Numbers, null) },
                    onClick = { expanded = false; onWordCount() },
                )
                if (lightPages != null) {
                    DropdownMenuItem(
                        text = { Text("Light pages") },
                        leadingIcon = { Icon(Icons.Outlined.LightMode, null) },
                        trailingIcon = { Checkbox(checked = lightPages, onCheckedChange = null) },
                        onClick = { expanded = false; onLightPages(!lightPages) },
                    )
                }
                onDiscard?.let { discard ->
                    DropdownMenuItem(
                        text = { Text("Discard changes") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Undo, null) },
                        onClick = { expanded = false; discard() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Close tab") },
                    leadingIcon = { Icon(Icons.Outlined.Close, null) },
                    onClick = { expanded = false; onClose() },
                )
            }
            onCloseAll?.let { closeAll ->
                DropdownMenuItem(
                    text = { Text("Close all tabs") },
                    leadingIcon = { Icon(Icons.Outlined.ClearAll, null) },
                    onClick = { expanded = false; closeAll() },
                )
            }
            onClearHome?.let { clear ->
                DropdownMenuItem(
                    text = { Text("Clear Home") },
                    leadingIcon = { Icon(Icons.Outlined.CleaningServices, null) },
                    onClick = { expanded = false; clear() },
                )
            }
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { expanded = false; onSettings() },
            )
        }
    }
}

/** True while a document is shown full screen; viewers hide their own toolbars then. */
val LocalFullScreen = staticCompositionLocalOf { false }

/** Hides the status and navigation bars while [fullScreen]; a swipe from an edge shows them briefly. */
@Composable
private fun FullScreenEffect(fullScreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullScreen) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (fullScreen) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** The app's name and caption, with its logo, for the top bar on Home. */
@Composable
private fun HomeTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.libreon_logo), null, Modifier.size(36.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
