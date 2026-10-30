// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.session.TextSession
import dev.offlineoffice.app.session.TextSession.Mode
import dev.offlineoffice.app.session.TextSession.Pane
import dev.offlineoffice.app.text.JsonFormatter
import dev.offlineoffice.app.ui.viewers.text.CsvTableView
import dev.offlineoffice.app.ui.viewers.text.EditorController
import dev.offlineoffice.app.ui.viewers.text.EditorPane
import dev.offlineoffice.app.ui.viewers.text.EncodingDialog
import dev.offlineoffice.app.ui.viewers.text.FindReplaceBar
import dev.offlineoffice.app.ui.viewers.text.FindState
import dev.offlineoffice.app.ui.viewers.text.GoToLineDialog
import dev.offlineoffice.app.ui.viewers.text.HugeFileChooser
import dev.offlineoffice.app.ui.viewers.text.IndexingMessage
import dev.offlineoffice.app.ui.viewers.text.JsonErrorDialog
import dev.offlineoffice.app.ui.viewers.text.LinkDialog
import dev.offlineoffice.app.ui.viewers.text.MarkdownPreview
import dev.offlineoffice.app.ui.viewers.text.ReadOnlyLinesView
import dev.offlineoffice.app.ui.viewers.text.formatSize
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Width from which Markdown can show editor and preview side by side. */
private val SPLIT_MIN_WIDTH = 840.dp

private data class EditorStats(val line: Int, val column: Int, val lines: Int, val chars: Int)

/**
 * The text document area: toolbar, find/replace, the editor (or read-only line list, Markdown
 * preview, CSV table) and a status line with position, counts, encoding and line endings.
 */
@OptIn(FlowPreview::class)
@Composable
fun TextEditorView(session: TextSession) {
    val find = remember(session) { FindState() }
    val controller = remember(session) { EditorController() }
    val listState = remember(session, session.index) { LazyListState(session.firstVisibleLine) }
    val scope = rememberCoroutineScope()
    var goToLine by remember { mutableStateOf(false) }
    var encodingDialog by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    var jsonError by remember { mutableStateOf<JsonFormatter.Result.Invalid?>(null) }
    val mode = session.mode
    val editable = mode == Mode.Editable
    val index = session.index

    val stats by produceState(EditorStats(1, 1, 1, 0), session) {
        snapshotFlow { session.state.text to session.state.selection }.debounce(150).collectLatest { (snapshot, selection) ->
            value = withContext(Dispatchers.Default) {
                val cursor = selection.start.coerceIn(0, snapshot.length)
                var line = 1
                var lineStart = 0
                var lines = 1
                for (i in 0 until snapshot.length) {
                    if (snapshot[i] == '\n') {
                        lines++
                        if (i < cursor) { line++; lineStart = i + 1 }
                    }
                }
                EditorStats(line, cursor - lineStart + 1, lines, snapshot.length)
            }
        }
    }

    // Editor search: recompute matches when the query or text changes; jump only when the query does.
    LaunchedEffect(session, editable) {
        if (!editable) return@LaunchedEffect
        var lastQuery = "" to false
        snapshotFlow { Triple(find.visible, find.query to find.matchCase, session.state.text to session.state.selection) }
            .debounce(200)
            .collectLatest { (visible, q, textAndSelection) ->
                val (snapshot, selection) = textAndSelection
                if (!visible || q.first.isEmpty()) {
                    find.matches = emptyList(); find.current = -1; lastQuery = q; return@collectLatest
                }
                val text = snapshot.toString()
                val found = withContext(Dispatchers.Default) { findAll(text, q.first, !q.second) }
                find.matches = found
                val sel = selection.start
                val exact = found.binarySearch(sel)
                val queryChanged = q != lastQuery
                lastQuery = q
                find.current = when {
                    found.isEmpty() -> -1
                    exact >= 0 && !queryChanged -> exact
                    else -> (if (exact >= 0) exact else -exact - 1).let { if (it >= found.size) 0 else it }
                }
                if (queryChanged && find.current >= 0) {
                    val s = found[find.current]
                    controller.reveal(session, s, s + q.first.length)
                }
            }
    }

    // Read-only search: scan the file in the background.
    LaunchedEffect(session, index) {
        if (index == null) return@LaunchedEffect
        snapshotFlow { Triple(find.visible, find.query, find.matchCase) }
            .debounce(300)
            .collectLatest { (visible, query, matchCase) ->
                find.capped = false
                if (!visible || query.isEmpty()) { find.matches = emptyList(); find.current = -1; return@collectLatest }
                find.progress = 0f
                try {
                    val hits = session.searchLines(query, matchCase, LARGE_SEARCH_LIMIT) { p -> find.progress = p }
                    find.matches = hits
                    find.capped = hits.size >= LARGE_SEARCH_LIMIT
                    val first = hits.binarySearch(listState.firstVisibleItemIndex).let { if (it >= 0) it else -it - 1 }
                    find.current = if (hits.isEmpty()) -1 else first.coerceAtMost(hits.lastIndex)
                    if (find.current >= 0) listState.scrollToItem(hits[find.current])
                } finally {
                    find.progress = null
                }
            }
    }

    fun step(delta: Int) {
        val matches = find.matches
        if (matches.isEmpty()) return
        find.current = ((find.current + delta) % matches.size + matches.size) % matches.size
        val target = matches[find.current]
        scope.launch {
            if (editable) controller.reveal(session, target, target + find.query.length) else listState.animateScrollToItem(target)
        }
    }

    fun replaceCurrent() {
        val start = find.matches.getOrNull(find.current) ?: return
        val end = start + find.query.length
        val text = session.state.text
        if (end > text.length || !text.substring(start, end).equals(find.query, ignoreCase = !find.matchCase)) return
        session.state.edit {
            replace(start, end, find.replacement)
            selection = TextRange(start + find.replacement.length)
        }
        // Recompute now so "Replace" can be pressed repeatedly, then move to the next match.
        val found = findAll(session.state.text.toString(), find.query, !find.matchCase)
        find.matches = found
        val next = found.binarySearch(start + find.replacement.length).let { if (it >= 0) it else -it - 1 }
        find.current = if (found.isEmpty()) -1 else if (next >= found.size) 0 else next
        if (find.current >= 0) {
            val s = found[find.current]
            scope.launch { controller.reveal(session, s, s + find.query.length) }
        }
    }

    fun replaceAll() {
        val query = find.query
        if (query.isEmpty()) return
        val text = session.state.text.toString()
        val found = findAll(text, query, !find.matchCase)
        if (found.isEmpty()) return
        val sb = StringBuilder(text.length)
        var from = 0
        for (s in found) {
            sb.append(text, from, s).append(find.replacement)
            from = s + query.length
        }
        sb.append(text, from, text.length)
        session.replaceAll(sb.toString()) // one undoable edit
        find.matches = emptyList()
        find.current = -1
    }

    fun formatJson() {
        when (val result = JsonFormatter.format(session.state.text.toString())) {
            is JsonFormatter.Result.Formatted -> session.replaceAll(result.text)
            is JsonFormatter.Result.Invalid -> jsonError = result
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= SPLIT_MIN_WIDTH
        val pane = session.pane.let { if (it == Pane.SPLIT && !wide) Pane.TEXT else it }
        Column(Modifier.fillMaxSize()) {
            Toolbar(
                session = session,
                editable = editable,
                pane = pane,
                wide = wide,
                onFind = { find.visible = !find.visible },
                onGoToLine = { goToLine = true },
                onEncoding = { encodingDialog = true },
                onFormatJson = ::formatJson,
            )
            if (find.visible && mode !is Mode.Huge && pane in setOf(Pane.TEXT, Pane.SPLIT)) {
                FindReplaceBar(
                    find = find,
                    canReplace = editable,
                    onNext = { step(1) },
                    onPrevious = { step(-1) },
                    onReplace = ::replaceCurrent,
                    onReplaceAll = ::replaceAll,
                    onClose = { find.visible = false },
                )
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    mode is Mode.Huge -> HugeFileChooser(
                        mode.sizeBytes,
                        onHead = { session.openWindow(TextSession.Window.HEAD) },
                        onTail = { session.openWindow(TextSession.Window.TAIL) },
                    )
                    mode is Mode.ReadOnly && index == null -> IndexingMessage(session.indexProgress, session.indexError)
                    mode is Mode.ReadOnly && index != null -> ReadOnlyLinesView(session, index, listState, find)
                    pane == Pane.PREVIEW -> MarkdownPreview(session.state.text.toString(), onLink = { link = it })
                    // The table is a snapshot taken when it is shown; editing happens in Text mode.
                    pane == Pane.TABLE -> CsvTableView(remember { session.state.text.toString() })
                    pane == Pane.SPLIT -> Row(Modifier.fillMaxSize()) {
                        EditorPane(session, controller, find, Modifier.weight(1f))
                        VerticalDivider()
                        val preview by produceState(session.state.text.toString(), session) {
                            snapshotFlow { session.state.text }.debounce(400).collectLatest { value = it.toString() }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) { MarkdownPreview(preview, onLink = { link = it }) }
                    }
                    else -> EditorPane(session, controller, find)
                }
            }
            StatusLine(session, stats, index?.lineCount, onEncoding = { encodingDialog = true })
        }
    }

    if (goToLine) {
        val lineCount = if (editable) stats.lines else index?.lineCount ?: 1
        GoToLineDialog(
            lineCount = lineCount,
            onGo = { line ->
                goToLine = false
                scope.launch {
                    if (editable) {
                        session.pane = session.pane.takeIf { it == Pane.SPLIT } ?: Pane.TEXT
                        controller.reveal(session, offsetOfLine(session.state.text, line))
                    } else {
                        listState.scrollToItem(line - 1)
                    }
                }
            },
            onDismiss = { goToLine = false },
        )
    }
    if (encodingDialog) {
        EncodingDialog(
            current = session.format,
            editable = editable,
            onApply = { session.changeFormat(it); encodingDialog = false },
            onDismiss = { encodingDialog = false },
        )
    }
    link?.let { LinkDialog(it, onDismiss = { link = null }) }
    jsonError?.let { err ->
        JsonErrorDialog(
            message = err.message, line = err.line, column = err.column,
            onShow = {
                jsonError = null
                session.pane = Pane.TEXT
                scope.launch { controller.reveal(session, err.offset) }
            },
            onDismiss = { jsonError = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class) // TextFieldState.undoState
@Composable
private fun Toolbar(
    session: TextSession,
    editable: Boolean,
    pane: Pane,
    wide: Boolean,
    onFind: () -> Unit,
    onGoToLine: () -> Unit,
    onEncoding: () -> Unit,
    onFormatJson: () -> Unit,
) {
    val mode = session.mode
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editable) {
            val undo = session.state.undoState
            IconButton(onClick = { undo.undo() }, enabled = undo.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
            IconButton(onClick = { undo.redo() }, enabled = undo.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
        }
        if (mode !is Mode.Huge) {
            IconButton(onClick = onFind, enabled = pane == Pane.TEXT || pane == Pane.SPLIT || !editable) {
                Icon(Icons.Outlined.Search, "Find and replace")
            }
        }
        if (editable) {
            when (session.kind) {
                DocumentKind.MARKDOWN -> PaneSwitch(
                    options = listOfNotNull(Pane.TEXT to "Edit", Pane.PREVIEW to "Preview", (Pane.SPLIT to "Split").takeIf { wide }),
                    selected = pane,
                    onSelect = { session.pane = it },
                )
                DocumentKind.CSV -> PaneSwitch(
                    options = listOf(Pane.TEXT to "Text", Pane.TABLE to "Table"),
                    selected = pane,
                    onSelect = { session.pane = it },
                )
                else -> if (session.isJson) TextButton(onClick = onFormatJson) { Text("Format") }
            }
        } else {
            ReadOnlyBadge(mode)
        }
        Spacer(Modifier.weight(1f))
        MoreMenu(session, canGoToLine = mode !is Mode.Huge && (pane == Pane.TEXT || pane == Pane.SPLIT || !editable), onGoToLine, onEncoding)
    }
}

@Composable
private fun PaneSwitch(options: List<Pair<Pane, String>>, selected: Pane, onSelect: (Pane) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 4.dp)) {
        options.forEachIndexed { i, (pane, label) ->
            SegmentedButton(
                selected = pane == selected,
                onClick = { onSelect(pane) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun ReadOnlyBadge(mode: Mode) {
    val window = (mode as? Mode.ReadOnly)?.window
    val suffix = when (window) {
        TextSession.Window.HEAD -> " · first 4 MB"
        TextSession.Window.TAIL -> " · last 4 MB"
        else -> ""
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.padding(end = 4.dp).width(16.dp))
            Text("Read-only (large file)$suffix", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MoreMenu(session: TextSession, canGoToLine: Boolean, onGoToLine: () -> Unit, onEncoding: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "Text options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Go to line…") },
                enabled = canGoToLine,
                onClick = { open = false; onGoToLine() },
            )
            DropdownMenuItem(
                text = { Text("Word wrap") },
                trailingIcon = { if (session.wrap) Icon(Icons.Outlined.Check, "On") },
                onClick = { session.wrap = !session.wrap; open = false },
            )
            DropdownMenuItem(
                text = { Text("Monospace font") },
                trailingIcon = { if (session.monospace) Icon(Icons.Outlined.Check, "On") },
                onClick = { session.monospace = !session.monospace; open = false },
            )
            DropdownMenuItem(text = { Text("Encoding and line endings…") }, onClick = { open = false; onEncoding() })
            if ((session.mode as? Mode.ReadOnly)?.window.let { it == TextSession.Window.HEAD || it == TextSession.Window.TAIL }) {
                DropdownMenuItem(text = { Text("Choose beginning or end…") }, onClick = { open = false; session.closeWindow() })
            }
        }
    }
}

@Composable
private fun StatusLine(session: TextSession, stats: EditorStats, readOnlyLines: Int?, onEncoding: () -> Unit) {
    val nf = remember { NumberFormat.getIntegerInstance() }
    val left = when (val mode = session.mode) {
        Mode.Editable -> "Ln ${nf.format(stats.line)}, Col ${nf.format(stats.column)} · ${nf.format(stats.lines)} lines · ${nf.format(stats.chars)} chars"
        is Mode.ReadOnly -> (readOnlyLines?.let { "${nf.format(it)} lines · " } ?: "") + formatSize(mode.sizeBytes)
        is Mode.Huge -> formatSize(mode.sizeBytes)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(left, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (session.lossy) {
                Text("Undecodable bytes replaced", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                session.format.label,
                Modifier.clickable(onClick = onEncoding).padding(vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

private const val LARGE_SEARCH_LIMIT = 10_000
private const val EDITOR_MATCH_LIMIT = 100_000

/** Start offsets of every occurrence of [query] in [text] (non-overlapping). */
private fun findAll(text: String, query: String, ignoreCase: Boolean): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (out.size < EDITOR_MATCH_LIMIT) {
        val at = text.indexOf(query, from, ignoreCase)
        if (at < 0) break
        out += at
        from = at + query.length
    }
    return out
}

/** Character offset where 1-based [line] starts. */
private fun offsetOfLine(text: CharSequence, line: Int): Int {
    if (line <= 1) return 0
    var current = 1
    for (i in text.indices) {
        if (text[i] == '\n' && ++current == line) return i + 1
    }
    return text.length
}
