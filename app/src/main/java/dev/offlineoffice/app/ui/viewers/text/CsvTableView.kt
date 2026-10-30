// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.text

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.text.CsvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class CsvTable(val rows: List<List<String>>, val widths: List<Dp>, val delimiter: Char)

private const val MAX_COLUMNS = 500
private const val MAX_CELL_CHARS = 300

/** Read-only grid of a CSV/TSV text. The first record is shown as a sticky header. */
@Composable
fun CsvTableView(text: String) {
    val table by produceState<CsvTable?>(null, text) {
        value = withContext(Dispatchers.Default) {
            val delimiter = CsvParser.detectDelimiter(text)
            val rows = CsvParser.parse(text, delimiter).map { row -> row.take(MAX_COLUMNS) }
            val columns = rows.maxOfOrNull { it.size } ?: 0
            val widths = (0 until columns).map { c ->
                val chars = rows.asSequence().take(500).maxOfOrNull { r -> r.getOrNull(c)?.lineSequence()?.maxOfOrNull { it.length } ?: 0 } ?: 0
                (chars * 8 + 24).coerceIn(56, 320).dp
            }
            CsvTable(rows, widths, delimiter)
        }
    }
    val t = table ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    if (t.rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No rows") }
        return
    }
    val colors = MaterialTheme.colorScheme
    val numberWidth = (t.rows.size.toString().length * 9 + 20).dp
    val totalWidth = numberWidth + t.widths.fold(0.dp) { a, b -> a + b }
    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(totalWidth).fillMaxHeight()) {
            stickyHeader {
                CsvRow("", t.rows[0], t.widths, numberWidth, header = true)
            }
            itemsIndexed(t.rows.subList(1, t.rows.size)) { i, row ->
                CsvRow((i + 2).toString(), row, t.widths, numberWidth, header = false)
            }
        }
    }
}

@Composable
private fun CsvRow(number: String, cells: List<String>, widths: List<Dp>, numberWidth: Dp, header: Boolean) {
    val colors = MaterialTheme.colorScheme
    val bg = if (header) colors.surfaceContainerHigh else colors.surface
    val style = MaterialTheme.typography.bodySmall.let { if (header) it.copy(fontWeight = FontWeight.SemiBold) else it }
    Row(Modifier.background(bg).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Text(
            number,
            Modifier.width(numberWidth).fillMaxHeight().background(colors.surfaceContainer)
                .border(0.5.dp, colors.outlineVariant).padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        widths.forEachIndexed { c, w ->
            val value = cells.getOrNull(c).orEmpty()
            Text(
                if (value.length > MAX_CELL_CHARS) value.take(MAX_CELL_CHARS) + "…" else value,
                Modifier.width(w).fillMaxHeight().border(0.5.dp, colors.outlineVariant).padding(horizontal = 6.dp, vertical = 4.dp),
                style = style,
                color = colors.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
