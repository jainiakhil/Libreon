// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import android.view.SurfaceHolder
import android.view.SurfaceView
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
import dev.offlineoffice.app.session.OfficeSession
import dev.offlineoffice.app.ui.CenteredMessage
import dev.offlineoffice.office.OfficeClient

/** The engine's page for an Office document, drawn by the engine process into a surface of ours. */
@Composable
fun OfficeView(session: OfficeSession, onSave: () -> Unit) {
    val document = session.document
    val state by document.state.collectAsState()
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(document) { document.saveRequests.collect { currentOnSave() } }

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
            modifier = Modifier.fillMaxSize(),
        )
        when (val s = state) {
            OfficeClient.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is OfficeClient.State.Failed -> CenteredMessage(s.message)
            OfficeClient.State.Ready -> Unit
        }
    }
}
