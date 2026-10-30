// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * Pick a saved signature or initials, draw a new one, or import an image with the system
 * Photo Picker (no permission needed). Saved entries can be deleted here.
 */
@Composable
fun SignatureSheet(onDismiss: () -> Unit, onPick: (Bitmap, initials: Boolean) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { SignatureStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var initials by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    val saved by produceState<List<SavedSignature>>(emptyList(), reload) { value = store.list() }

    fun pick(signature: SavedSignature) = scope.launch {
        val bitmap = store.loadBitmap(signature)
        if (bitmap != null) onPick(bitmap, signature.initials) else onMessage("Could not load the signature")
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { store.importImage(context, uri, initials) }
                .onSuccess { pick(it) }
                .onFailure { onMessage("Could not import the image") }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Signatures", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !initials, onClick = { initials = false }, label = { Text("Signature") })
                    FilterChip(selected = initials, onClick = { initials = true }, label = { Text("Initials") })
                }
                Spacer(Modifier.height(8.dp))
                val shown = saved.filter { it.initials == initials }
                if (shown.isEmpty()) {
                    Text(
                        if (initials) "No saved initials yet." else "No saved signatures yet.",
                        Modifier.padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.id }) { signature ->
                            SavedRow(
                                signature = signature,
                                store = store,
                                onPick = { pick(signature) },
                                onDelete = { scope.launch { store.delete(signature); reload++ } },
                            )
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { drawing = true }) {
                        Icon(Icons.Outlined.Draw, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Draw new")
                    }
                    OutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import image")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text(
                    "Signatures are stored only on this device.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (drawing) {
        SignaturePadDialog(
            initials = initials,
            onDismiss = { drawing = false },
            onDone = { ink ->
                drawing = false
                scope.launch {
                    runCatching { store.saveInk(ink, initials) }
                        .onSuccess { pick(it) }
                        .onFailure { onMessage("Could not save the signature") }
                }
            },
        )
    }
}

@Composable
private fun SavedRow(signature: SavedSignature, store: SignatureStore, onPick: () -> Unit, onDelete: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, signature.id) { value = store.loadBitmap(signature) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(64.dp).background(Color.White, MaterialTheme.shapes.small).padding(4.dp)) {
            bitmap?.let {
                Image(it.asImageBitmap(), "Saved signature", Modifier.fillMaxWidth().height(56.dp), contentScale = ContentScale.Fit)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
    }
}
