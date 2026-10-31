// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import android.view.ViewGroup
import android.widget.FrameLayout
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
import dev.offlineoffice.office.OfficeDocument

/** The engine's front-end page for an Office document. The page outlives this view. */
@Composable
fun OfficeView(session: OfficeSession, onSave: () -> Unit) {
    val document = session.document
    val state by document.state.collectAsState()
    val currentOnSave by rememberUpdatedState(onSave)
    LaunchedEffect(document) { document.saveRequests.collect { currentOnSave() } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                document.attachTo(context)
                FrameLayout(context).apply {
                    addView(document.webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { document.detach() },
            modifier = Modifier.fillMaxSize(),
        )
        when (val s = state) {
            OfficeDocument.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is OfficeDocument.State.Failed -> CenteredMessage(s.message)
            OfficeDocument.State.Ready -> Unit
        }
    }
}
