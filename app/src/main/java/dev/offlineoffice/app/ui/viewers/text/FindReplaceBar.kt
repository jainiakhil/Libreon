// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Find/replace state shared by the bar and the views. [matches] are character offsets in the
 * editor, or line numbers in the read-only view; [current] indexes into it (-1 = none).
 */
@Stable
class FindState {
    var visible by mutableStateOf(false)
    var replaceVisible by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var matches by mutableStateOf<List<Int>>(emptyList())
    var current by mutableIntStateOf(-1)
    /** Read-only search in progress (0..1), or null. */
    var progress by mutableStateOf<Float?>(null)
    /** Results were capped at the search limit. */
    var capped by mutableStateOf(false)

    val countLabel: String
        get() = when {
            query.isEmpty() -> ""
            progress != null -> "…"
            matches.isEmpty() -> "No results"
            else -> "${if (current >= 0) current + 1 else 0} of ${matches.size}${if (capped) "+" else ""}"
        }
}

/** The find & replace bar. Replace controls appear only when [canReplace]. */
@Composable
fun FindReplaceBar(
    find: FindState,
    canReplace: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find.query,
                    onValueChange = { find.query = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Find") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    suffix = { Text(find.countLabel, style = MaterialTheme.typography.labelMedium) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                )
                IconButton(onClick = onPrevious, enabled = find.matches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "Previous match")
                }
                IconButton(onClick = onNext, enabled = find.matches.isNotEmpty()) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "Next match")
                }
                if (canReplace) {
                    IconButton(onClick = { find.replaceVisible = !find.replaceVisible }) {
                        Icon(
                            if (find.replaceVisible) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                            if (find.replaceVisible) "Hide replace" else "Show replace",
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = find.matchCase,
                    onClick = { find.matchCase = !find.matchCase },
                    label = { Text("Match case") },
                )
                Spacer(Modifier.width(8.dp))
                if (canReplace && find.replaceVisible) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReplace, enabled = find.current >= 0) { Text("Replace") }
                    TextButton(onClick = onReplaceAll, enabled = find.matches.isNotEmpty()) { Text("Replace all") }
                }
            }
            if (canReplace && find.replaceVisible) {
                OutlinedTextField(
                    value = find.replacement,
                    onValueChange = { find.replacement = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    placeholder = { Text("Replace with") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
