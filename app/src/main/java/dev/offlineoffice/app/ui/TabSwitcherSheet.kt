// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.offlineoffice.app.data.TabEntity

/** Phone-sized document switcher: every open tab with type, unsaved marker and close. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcherSheet(
    tabs: List<TabEntity>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (TabEntity) -> Unit,
    onMove: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Open documents (${tabs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) {
                Icon(Icons.Outlined.Add, null)
                Text("Open")
            }
        }
        LazyColumn(Modifier.padding(bottom = 24.dp)) {
            itemsIndexed(tabs, key = { _, t -> t.id }) { index, tab ->
                ListItem(
                    modifier = Modifier.padding(horizontal = 8.dp).clickable { onSelect(tab.id) },
                    colors = if (tab.id == activeId) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    leadingContent = { Icon(tab.kind.icon, null) },
                    headlineContent = {
                        Text(tab.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = if (tab.dirty) ({ Text("Unsaved changes") }) else null,
                    trailingContent = {
                        Row {
                            if (index > 0) IconButton(onClick = { onMove(tab.id, index - 1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                            if (index < tabs.lastIndex) IconButton(onClick = { onMove(tab.id, index + 1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                            IconButton(onClick = { onClose(tab) }) { Icon(Icons.Outlined.Close, "Close ${tab.displayName}") }
                        }
                    },
                )
            }
        }
    }
}
