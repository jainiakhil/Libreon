// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.PdfSession
import dev.offlineoffice.app.ui.LocalFullScreen
import dev.offlineoffice.app.ui.openExternalLink
import dev.offlineoffice.app.text.WordCounter
import dev.offlineoffice.pdf.AnnotType
import dev.offlineoffice.pdf.FormType

/**
 * The PDF viewer: continuous pages with pinch/button zoom and sharp re-rendering, thumbnails,
 * search, AcroForm filling, flat-form marks and signatures.
 */
@Composable
fun PdfViewer(session: PdfSession) {
    val locked by session.locked.collectAsState()
    if (locked) {
        PasswordPrompt(session)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(session.initialPage, session.initialOffset)
    val hScroll = rememberScrollState()
    val onMessage: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val state = remember(session) { PdfViewerState(session, listState, hScroll, scope, onMessage) }
    // True size: a point is 1/72 inch, and Android draws 160 dp per inch.
    state.actualScale = LocalDensity.current.density * 160f / 72f
    var showThumbnails by remember { mutableStateOf(false) }
    var showSignatures by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (page, offset) ->
                session.currentPage = page
                session.currentOffset = offset
            }
    }
    LaunchedEffect(session) { session.findRequests.collect { state.searchOpen = true } }
    // Tapping a signature field opens the picker for it.
    LaunchedEffect(state) {
        snapshotFlow { state.signatureTarget }.collect { if (it != null) showSignatures = true }
    }

    Column(Modifier.fillMaxSize()) {
        if (!LocalFullScreen.current) {
            PdfToolbar(
                state = state,
                onThumbnails = { state.commitEdit(); state.finishDrawing(); showThumbnails = true },
                onSign = { state.commitEdit(); state.finishDrawing(); state.signatureTarget = null; showSignatures = true },
            )
        }
        if (session.formType == FormType.XFA_FULL) {
            Notice("This form uses XFA, which is not supported. Only its static content is shown and it cannot be filled.")
        }
        if (state.searchOpen) SearchBar(state)

        val density = LocalDensity.current
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF5F6368)).clipToBounds()) {
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            state.viewport = viewport
            state.paddingPx = with(density) { 8.dp.toPx() }
            val contentWidth = with(density) { state.contentWidthPx.toDp() }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state) { detectPinch(state) }
                    .graphicsLayer {
                        val g = state.gestureScale
                        scaleX = g
                        scaleY = g
                        transformOrigin = TransformOrigin(
                            state.gestureFocal.x / viewport.width.coerceAtLeast(1),
                            state.gestureFocal.y / viewport.height.coerceAtLeast(1),
                        )
                    }
                    .horizontalScroll(hScroll, enabled = state.contentWidthPx > viewport.width),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.width(contentWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(session.pageSizes.size) { index -> PdfPageItem(state, index) }
                }
            }
            // Floats over the pages so the layout (and the tap target) does not shift when it goes.
            state.tool?.let { tool ->
                Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    Notice(tool.hint, action = "Cancel") { state.tool = null }
                }
            }
            state.selection?.let { selection ->
                Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) { SelectionBar(state, selection) }
            }
            state.selectedHighlight?.let { annotation ->
                Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    val label = if (annotation.type == AnnotType.NOTE) "Note: ${state.selectedNoteText.orEmpty()}" else annotation.type.label
                    Notice(label, action = "Remove", onAction = state::removeSelectedHighlight)
                }
            }
            state.drawTool?.let { tool ->
                Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) { DrawBar(state, tool) }
            }
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
            ) {
                Text(
                    "${listState.firstVisibleItemIndex + 1} / ${session.pageSizes.size}",
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (showThumbnails) {
        PagesSheet(
            session = session,
            current = listState.firstVisibleItemIndex,
            onPick = { page -> showThumbnails = false; state.jumpTo(page) },
            onDismiss = { showThumbnails = false },
            onMessage = onMessage,
        )
    }
    state.noteDraft?.let { NoteDialog(onAdd = state::addNote, onDismiss = { state.noteDraft = null }) }
    state.pendingLink?.let { LinkDialog(it, onDismiss = { state.pendingLink = null }) }
    if (showSignatures) {
        SignatureSheet(
            onDismiss = { showSignatures = false; state.signatureTarget = null },
            onPick = { bitmap, initials -> showSignatures = false; state.placeSignature(bitmap, initials) },
            onMessage = onMessage,
        )
    }
}

/** Two-finger pinch: scales the viewport visually, then commits the zoom on release. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPinch(state: PdfViewerState) {
    awaitEachGesture {
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                if (!pinching) {
                    pinching = true
                    state.commitEdit()
                    state.gestureFocal = event.calculateCentroid(useCurrent = true)
                }
                val min = state.minZoom / state.zoom
                val max = state.maxZoom / state.zoom
                state.gestureScale = (state.gestureScale * event.calculateZoom()).coerceIn(min, max)
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (pinching) state.endPinch()
    }
}

@Composable
private fun PdfToolbar(state: PdfViewerState, onThumbnails: () -> Unit, onSign: () -> Unit) {
    var fillMenu by remember { mutableStateOf(false) }
    var zoomMenu by remember { mutableStateOf(false) }
    var annotateMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onThumbnails) { Icon(Icons.Outlined.GridView, "Pages") }
            IconButton(onClick = { state.searchOpen = !state.searchOpen; if (!state.searchOpen) state.closeSearch() }) {
                Icon(Icons.Outlined.Search, "Search")
            }
            IconButton(onClick = { state.zoomBy(1 / 1.25f) }, enabled = state.zoom > state.minZoom * 1.001f) {
                Icon(Icons.Outlined.ZoomOut, "Zoom out")
            }
            Box {
                TextButton(onClick = { zoomMenu = true }) { Text("${state.percent}%", style = MaterialTheme.typography.labelLarge) }
                DropdownMenu(expanded = zoomMenu, onDismissRequest = { zoomMenu = false }) {
                    DropdownMenuItem(text = { Text("Fit width") }, onClick = { zoomMenu = false; state.zoomTo(1f) })
                    DropdownMenuItem(text = { Text("Fit page") }, onClick = { zoomMenu = false; state.zoomTo(state.fitPageZoom) })
                    PdfViewerState.ZOOM_PRESETS.forEach { percent ->
                        val zoom = state.zoomForPercent(percent)
                        if (zoom in state.minZoom..state.maxZoom) {
                            DropdownMenuItem(text = { Text("$percent%") }, onClick = { zoomMenu = false; state.zoomTo(zoom) })
                        }
                    }
                }
            }
            IconButton(onClick = { state.zoomBy(1.25f) }, enabled = state.zoom < state.maxZoom * 0.999f) {
                Icon(Icons.Outlined.ZoomIn, "Zoom in")
            }
            Spacer(Modifier.width(8.dp))
            Box {
                TextButton(onClick = { state.commitEdit(); annotateMenu = true }) {
                    Icon(Icons.Outlined.Brush, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Annotate")
                }
                DropdownMenu(expanded = annotateMenu, onDismissRequest = { annotateMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Draw") },
                        leadingIcon = { Icon(Icons.Outlined.Gesture, null) },
                        onClick = { annotateMenu = false; state.startDrawing(DrawTool.INK) },
                    )
                    DropdownMenuItem(
                        text = { Text("Rectangle") },
                        leadingIcon = { Icon(Icons.Outlined.CropSquare, null) },
                        onClick = { annotateMenu = false; state.startDrawing(DrawTool.RECT) },
                    )
                    DropdownMenuItem(
                        text = { Text("Ellipse") },
                        leadingIcon = { Icon(Icons.Outlined.Circle, null) },
                        onClick = { annotateMenu = false; state.startDrawing(DrawTool.ELLIPSE) },
                    )
                    DropdownMenuItem(
                        text = { Text("Note") },
                        leadingIcon = { Icon(Icons.Outlined.StickyNote2, null) },
                        onClick = { annotateMenu = false; state.finishDrawing(); state.tool = PlaceTool.NOTE },
                    )
                    DropdownMenuItem(
                        text = { Text("Highlight or underline text") },
                        leadingIcon = { Icon(Icons.Outlined.BorderColor, null) },
                        onClick = { annotateMenu = false; state.showMessage("Long-press a word and drag to select text, then choose a highlight colour, underline or strike-out.") },
                    )
                }
            }
            Box {
                TextButton(onClick = { state.commitEdit(); state.finishDrawing(); fillMenu = true }) {
                    Icon(Icons.Outlined.EditNote, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fill")
                }
                DropdownMenu(expanded = fillMenu, onDismissRequest = { fillMenu = false }) {
                    DropdownMenuItem(text = { Text("Add text") }, onClick = { fillMenu = false; state.tool = PlaceTool.TEXT })
                    DropdownMenuItem(text = { Text("Add check mark  ✓") }, onClick = { fillMenu = false; state.tool = PlaceTool.CHECK })
                    DropdownMenuItem(text = { Text("Add cross mark  ✗") }, onClick = { fillMenu = false; state.tool = PlaceTool.CROSS })
                }
            }
            TextButton(onClick = onSign) {
                Icon(Icons.Outlined.Draw, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("Sign")
            }
        }
    }
}

@Composable
private fun SearchBar(state: PdfViewerState) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = state::closeSearch) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search") }
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { state.searchQuery = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Find in document") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.runSearch() }),
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { state.searchQuery = "" }) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
            )
            val total = state.searchMatches.size
            Text(
                when {
                    total == 0 && state.searching -> "Searching…"
                    total == 0 -> ""
                    else -> "${state.searchIndex + 1} of $total" + if (state.searching) "+" else ""
                },
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = { state.stepMatch(-1) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { state.stepMatch(1) }, enabled = total > 0) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
            TextButton(onClick = state::runSearch) { Text("Find") }
        }
    }
}

/** Actions for selected text: its word count, Copy, and highlight colours. */
@Suppress("DEPRECATION") // LocalClipboardManager: the replacement LocalClipboard needs ClipData plumbing for a plain string
@Composable
private fun SelectionBar(state: PdfViewerState, selection: PdfTextSelection) {
    val clipboard = LocalClipboardManager.current
    val words = remember(selection.text) { WordCounter.count(selection.text) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp) {
        // Scrolls sideways when the colours and actions do not fit a phone in portrait.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (words == 1) "1 word" else "$words words", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { clipboard.setText(AnnotatedString(selection.text.trim())); state.clearSelection() }) { Text("Copy") }
            PdfViewerState.HIGHLIGHT_COLORS.forEachIndexed { i, argb ->
                Box(
                    Modifier
                        .minimumInteractiveComponentSize()
                        .size(28.dp)
                        .background(Color(argb), CircleShape)
                        .clickable(onClickLabel = "Highlight in ${HIGHLIGHT_NAMES[i]}", role = Role.Button) { state.highlightSelection(argb) },
                )
            }
            IconButton(onClick = { state.highlightSelection(UNDERLINE_COLOR, AnnotType.UNDERLINE) }) {
                Icon(Icons.Outlined.FormatUnderlined, "Underline")
            }
            IconButton(onClick = { state.highlightSelection(STRIKE_COLOR, AnnotType.STRIKEOUT) }) {
                Icon(Icons.Outlined.FormatStrikethrough, "Strike out")
            }
            IconButton(onClick = state::clearSelection) { Icon(Icons.Outlined.Close, "Clear selection") }
        }
    }
}

private const val UNDERLINE_COLOR = 0xFF1E88E5.toInt()
private const val STRIKE_COLOR = 0xFFE53935.toInt()

/** While a drawing tool is on: its hint, colour choices and Done. */
@Composable
private fun DrawBar(state: PdfViewerState, tool: DrawTool) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(tool.hint, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PdfViewerState.INK_COLORS.forEachIndexed { i, argb ->
                    val chosen = state.inkColor == argb
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .size(if (chosen) 30.dp else 24.dp)
                            .background(Color(argb), CircleShape)
                            .border(if (chosen) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable(onClickLabel = "Draw in ${INK_NAMES[i]}", role = Role.RadioButton) { state.inkColor = argb },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = state::finishDrawing) { Text("Done") }
            }
        }
    }
}

private val INK_NAMES = listOf("red", "blue", "green", "black")

/** Asks for a new note's text. */
@Composable
private fun NoteDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.focusRequester(focus),
                placeholder = { Text("Note") },
                minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirms a tapped web or e-mail link before handing it to another app, so a stray tap never
 * leaves the document and the address can be checked first. Libreon itself never goes online.
 */
@Suppress("DEPRECATION") // LocalClipboardManager, as in the selection bar
@Composable
private fun LinkDialog(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val mail = uri.startsWith("mailto:", ignoreCase = true)
    val shown = if (mail) uri.substring(7).substringBefore('?') else uri
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mail) "Write an e-mail?" else "Open link?") },
        text = { Text(shown, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                openExternalLink(context, uri)
            }) { Text(if (mail) "Write" else "Open") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(shown))
                    onDismiss()
                }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private val HIGHLIGHT_NAMES = listOf("yellow", "green", "blue", "pink")

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
