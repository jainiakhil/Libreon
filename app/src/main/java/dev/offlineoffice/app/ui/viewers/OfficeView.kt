// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import dev.offlineoffice.app.OfficeApp
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.session.OfficeSession
import dev.offlineoffice.app.ui.LocalDarkTheme
import dev.offlineoffice.office.OfficeClient

/** The engine's page for an Office document, drawn by the engine process into a surface of ours. */
@Composable
fun OfficeView(session: OfficeSession, onSave: () -> Unit, onReload: () -> Unit) {
    val document = session.document
    val state by document.state.collectAsState()
    val wordCount by document.wordCount.collectAsState()
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(document) { document.saveRequests.collect { currentOnSave() } }
    val dark = LocalDarkTheme.current
    LaunchedEffect(document, dark) { document.setDarkTheme(dark) }
    var findOpen by rememberSaveable(session) { mutableStateOf(false) }
    LaunchedEffect(session) { session.findRequests.collect { findOpen = true } }

    Column(Modifier.fillMaxSize()) {
        if (session.readOnly) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "Opened read-only: this format can be viewed but not saved.",
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val settings = (LocalContext.current.applicationContext as OfficeApp).settings
        val tipSeen by settings.officeTipSeen.collectAsState()
        if (!tipSeen && state == OfficeClient.State.Ready) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tip: double-tap a word (or long-press it while reading) to select it, then drag the handles.",
                        Modifier.weight(1f).padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = settings::dismissOfficeTip) { Text("Got it") }
                }
            }
        }
        if (findOpen && state == OfficeClient.State.Ready) {
            OfficeFindBar(document, onClose = { findOpen = false; document.clearFind() })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        // Above our window, so touches (and with them focus and the keyboard) reach the page.
                        setZOrderOnTop(true)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = document.attach(this@apply)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                document.resize(this@apply, width, height)
                            override fun surfaceDestroyed(holder: SurfaceHolder) = document.detach(this@apply)
                        })
                    }
                },
                // The surface is drawn above everything of ours, so it stays transparent while the
                // page loads (letting the progress indicator show) and goes away if the engine fails.
                update = { view ->
                    view.alpha = if (state == OfficeClient.State.Ready) 1f else 0f
                    view.isVisible = state !is OfficeClient.State.Failed
                },
                modifier = Modifier.fillMaxSize(),
            )
            when (val s = state) {
                OfficeClient.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is OfficeClient.State.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = onReload, modifier = Modifier.padding(top = 16.dp)) { Text("Reload") }
                }
                OfficeClient.State.Ready -> Unit
            }
        }
        // Writer reports a live count (and the selection's) as the user types and selects.
        if (session.kind == DocumentKind.WORD && state == OfficeClient.State.Ready) {
            wordCount?.let { line ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Text(
                        line,
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Find in an Office document, through the engine's own search (it scrolls to and selects matches). */
@Composable
private fun OfficeFindBar(document: OfficeClient, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(document) { document.found.collect { count = it } }

    fun find(backward: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        if (q != searched) {
            // A new term: highlight and count every match, then go to the first.
            searched = q
            count = null
            document.find(q, all = true)
            document.find(q)
        } else {
            document.find(q, backward = backward)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close find") }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Find in document") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear") }
                    }
                },
            )
            Text(
                when (val c = count) {
                    null -> ""
                    0 -> "No matches"
                    1 -> "1 match"
                    else -> "$c matches"
                },
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            val canStep = searched != null && count != 0
            IconButton(onClick = { find(backward = true) }, enabled = canStep) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { find() }, enabled = canStep) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
            TextButton(onClick = { searched = null; find() }) { Text("Find") }
        }
    }
}
