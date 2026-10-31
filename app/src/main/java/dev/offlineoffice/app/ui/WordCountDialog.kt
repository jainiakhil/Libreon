// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.session.Count
import dev.offlineoffice.app.session.DocumentTools
import dev.offlineoffice.app.session.WordCount
import java.text.NumberFormat

/** Asks [tools] for the counts of the document called [displayName]. */
class WordCountRequest(val displayName: String, val tools: DocumentTools)

@Composable
fun WordCountDialog(request: WordCountRequest, onDismiss: () -> Unit) {
    val result by produceState<Result<WordCount>?>(null, request) {
        value = runCatching { request.tools.wordCount() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Word count") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val r = result
                when {
                    r == null -> CircularProgressIndicator(Modifier.padding(8.dp))
                    r.isFailure -> Text("Could not count: ${r.exceptionOrNull()?.message}")
                    else -> {
                        val count = r.getOrThrow()
                        CountRows("Document", count.document)
                        CountRows("Selection", count.selection, empty = "Nothing selected")
                        count.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun CountRows(label: String, count: Count?, empty: String = "Not available") {
    val nf = remember { NumberFormat.getIntegerInstance() }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (count == null) {
            Text(empty, style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Words")
                Text(nf.format(count.words))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Characters")
                Text(nf.format(count.chars))
            }
        }
    }
}
