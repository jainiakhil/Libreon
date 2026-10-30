// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown by Save when the session advises a new file (e.g. a signed PDF): saving a copy is
 * the default and keeps the original untouched; overwriting stays available.
 */
@Composable
fun SaveAsNewDialog(
    displayName: String,
    suggestedName: String,
    onSaveAsNew: () -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save signed document") },
        text = {
            Column {
                Text("Save the signed copy as a new file (\"$suggestedName\") and keep \"$displayName\" unchanged?")
            }
        },
        confirmButton = { TextButton(onClick = onSaveAsNew) { Text("Save as new file") } },
        dismissButton = {
            Column {
                TextButton(onClick = onOverwrite) { Text("Overwrite original") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
