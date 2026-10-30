// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.text

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.TextSession
import dev.offlineoffice.app.text.LineIndex
import java.util.Locale

/** Virtualised, read-only line list over a [LineIndex], with line numbers and find highlights. */
@Composable
fun ReadOnlyLinesView(session: TextSession, index: LineIndex, listState: LazyListState, find: FindState) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.copy(
        color = colors.onSurface,
        fontFamily = if (session.monospace) FontFamily.Monospace else FontFamily.Default,
    )
    val gutterStyle = style.copy(color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digits = index.lineCount.toString().length
    val gutterWidth = with(density) { measurer.measure("0".repeat(digits), gutterStyle).size.width.toDp() } + 12.dp
    val highlight = SpanStyle(background = colors.tertiary.copy(alpha = 0.3f))
    val currentLine = find.matches.getOrNull(find.current)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { session.firstVisibleLine = it }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Without wrapping, the whole list scrolls sideways together; its width fits the longest line.
        val contentWidth = if (session.wrap) maxWidth else {
            val charWidth = with(density) { measurer.measure("M", style).size.width.toDp() }
            maxOf(maxWidth, gutterWidth + charWidth * (index.longestLine + 2)).coerceAtMost(30_000.dp)
        }
        val outer = if (session.wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(rememberScrollState())
        Box(outer) {
            LazyColumn(state = listState, modifier = Modifier.width(contentWidth).fillMaxSize()) {
                items(index.lineCount) { i ->
                    val text = session.line(i)
                    val shown = remember(text, find.query, find.matchCase, find.visible) {
                        if (find.visible) highlighted(text, find.query, !find.matchCase, highlight) else AnnotatedString(text)
                    }
                    val rowModifier = if (i == currentLine) Modifier.background(colors.tertiary.copy(alpha = 0.12f)) else Modifier
                    Row(rowModifier.fillMaxWidth().padding(end = 8.dp)) {
                        Text(
                            String.format(Locale.ROOT, "%d", i + 1),
                            Modifier.width(gutterWidth).padding(end = 8.dp),
                            style = if (i == currentLine) gutterStyle.copy(color = colors.tertiary) else gutterStyle,
                            maxLines = 1,
                        )
                        Text(shown, style = style, softWrap = session.wrap)
                    }
                }
            }
        }
    }
}

private fun highlighted(text: String, query: String, ignoreCase: Boolean, style: SpanStyle): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase)
            if (at < 0) break
            append(text, from, at)
            withStyle(style) { append(text, at, at + query.length) }
            from = at + query.length
        }
        append(text, from, text.length)
    }
}

/** Index build progress, or an error. */
@Composable
fun IndexingMessage(progress: Float, error: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            Text("Could not read the file: $error", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("Indexing lines…", style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }
}

/** Shown for files over 50 MB: they cannot be opened whole, only a head or tail window. */
@Composable
fun HugeFileChooser(sizeBytes: Long, onHead: () -> Unit, onTail: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
        Text(
            "This file is ${formatSize(sizeBytes)}.",
            Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Files over 50 MB are too large to open whole. You can view the beginning or the end, read-only.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onHead) { Text("View first 4 MB") }
            OutlinedButton(onClick = onTail) { Text("View last 4 MB") }
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
