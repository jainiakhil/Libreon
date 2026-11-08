// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.offlineoffice.app.session.PdfSession
import dev.offlineoffice.pdf.PdfOpenException
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page thumbnails. Tapping one jumps to it; a long press starts selecting pages to rotate,
 * move, extract to a new PDF or delete. Pages of another PDF can be inserted too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagesSheet(session: PdfSession, current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    val selected = remember { mutableStateListOf<Int>() }
    var selecting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pageCount = session.pageSizes.size

    fun endSelection() {
        selecting = false
        selected.clear()
    }

    fun run(done: String, action: suspend () -> Boolean) {
        busy = true
        scope.launch {
            val ok = runCatching { action() }.getOrElse { false }
            busy = false
            onMessage(if (ok) done else "That didn't work")
            if (ok) endSelection()
        }
    }

    // Extract: the chosen pages become a new PDF, written where the user picks.
    var extracting by remember { mutableStateOf<List<Int>>(emptyList()) }
    val extract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val pages = extracting
        if (uri == null || pages.isEmpty()) return@rememberLauncherForActivityResult
        run(if (pages.size == 1) "Page saved as a new PDF" else "${pages.size} pages saved as a new PDF") {
            val temp = File(context.cacheDir, "extract.pdf")
            try {
                session.extractPages(pages, temp)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
                }
                true
            } finally {
                temp.delete()
            }
        }
    }
    // Insert: every page of a picked PDF goes after the selection (or at the end).
    val insert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val at = selected.maxOrNull()?.plus(1) ?: pageCount
        busy = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openFileDescriptor(uri, "r") }!!.use { session.insertPdf(it, at) }
            }
            busy = false
            onMessage(
                when {
                    result.exceptionOrNull().let { it is PdfOpenException && it.needsPassword } -> "That PDF is password protected"
                    result.getOrNull() == true -> "Pages inserted"
                    else -> "Could not insert that file"
                },
            )
            if (result.getOrNull() == true) endSelection()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selecting) "${selected.size} selected" else "Pages ($pageCount)",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (selecting) {
                        IconButton(onClick = ::endSelection) { Icon(Icons.Outlined.Close, "Cancel selection") }
                    } else {
                        IconButton(onClick = { selecting = true }, enabled = !busy) { Icon(Icons.Outlined.Checklist, "Select pages") }
                        IconButton(onClick = { insert.launch(arrayOf("application/pdf")) }, enabled = !busy) {
                            Icon(Icons.Outlined.NoteAdd, "Insert pages from another PDF")
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                    }
                }
                if (selecting) {
                    val sorted = selected.sorted()
                    val any = sorted.isNotEmpty() && !busy
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        TextButton(onClick = { run("Rotated") { session.rotatePages(sorted, 1) } }, enabled = any) {
                            Icon(Icons.Outlined.RotateRight, null)
                            Text(" Rotate")
                        }
                        TextButton(onClick = { moving = true }, enabled = any) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null)
                            Text(" Move")
                        }
                        TextButton(onClick = { extracting = sorted; extract.launch("Pages.pdf") }, enabled = any) {
                            Icon(Icons.Outlined.FileCopy, null)
                            Text(" Extract")
                        }
                        TextButton(onClick = { confirmDelete = true }, enabled = any && sorted.size < pageCount) {
                            Icon(Icons.Outlined.Delete, null)
                            Text(" Delete")
                        }
                    }
                    Text(
                        "Tap pages to select them.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    state = rememberLazyGridState(current.coerceIn(0, (pageCount - 1).coerceAtLeast(0))),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pageCount) { index ->
                        Thumbnail(
                            session, index, revision,
                            current = index == current,
                            checked = selecting && index in selected,
                            onClick = {
                                when {
                                    !selecting -> onPick(index)
                                    index in selected -> selected.remove(index)
                                    else -> selected.add(index)
                                }
                            },
                            onLongClick = {
                                selecting = true
                                if (index !in selected) selected.add(index)
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (n == 1) "Delete page ${selected.first() + 1}?" else "Delete $n pages?") },
            text = {
                val what = if (n == 1) "It is" else "They are"
                Text("$what removed from this document when you save it. Until then, Discard changes brings it all back.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val pages = selected.sorted()
                    run(if (pages.size == 1) "Page deleted" else "${pages.size} pages deleted") { session.deletePages(pages) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (moving) {
        var target by remember { mutableStateOf("") }
        val position = target.toIntOrNull()?.takeIf { it in 1..pageCount }
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to position") },
            text = {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.filter(Char::isDigit).take(6) },
                    label = { Text("Position (1 to $pageCount)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = position != null, onClick = {
                    moving = false
                    val pages = selected.sorted()
                    // The position counts in the document after the move.
                    val dest = (position!! - 1).coerceAtMost(pageCount - pages.size)
                    run("Moved") { session.movePages(pages, dest) }
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(
    session: PdfSession,
    index: Int,
    revision: Int,
    current: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val size = session.pageSizes[index]
    val bitmap by produceState<Bitmap?>(null, index, revision) {
        value = runCatching { session.renderPage(index, THUMB_WIDTH_PX) }.getOrNull()
    }
    val highlighted = checked || current
    Column(
        Modifier.combinedClickable(role = Role.Button, onClickLabel = "Page ${index + 1}", onLongClickLabel = "Select", onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(size.width / size.height)
                .background(Color.White)
                .border(if (highlighted) 3.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else Color.LightGray),
        ) {
            bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize()) }
            if (checked) {
                Icon(
                    Icons.Outlined.CheckCircle, "Selected",
                    Modifier.align(Alignment.TopEnd).padding(4.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
    }
}

private const val THUMB_WIDTH_PX = 240
