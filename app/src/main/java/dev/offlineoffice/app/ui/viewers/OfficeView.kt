// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import dev.offlineoffice.app.session.OfficeSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.offlineoffice.office.OfficeClient

/** The engine's page for an Office document, drawn by the engine process into a surface of ours. */
@Composable
fun OfficeView(session: OfficeSession, onSave: () -> Unit, onReload: () -> Unit) {
    val document = session.document
    val state by document.state.collectAsState()
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(document) { document.saveRequests.collect { currentOnSave() } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(document, dark) { document.setDarkTheme(dark) }

    Box(Modifier.fillMaxSize()) {
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
}
