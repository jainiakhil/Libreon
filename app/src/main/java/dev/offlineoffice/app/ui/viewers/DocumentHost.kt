// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.offlineoffice.app.session.DocumentSession
import dev.offlineoffice.app.session.OfficeSession
import dev.offlineoffice.app.session.PdfSession
import dev.offlineoffice.app.session.TextSession

/** Shows the viewer/editor for a loaded session. Keyed so each tab keeps its own UI state. */
@Composable
fun DocumentHost(session: DocumentSession) {
    key(session) {
        when (session) {
            is PdfSession -> PdfPagesView(session)
            is TextSession -> TextEditorView(session)
            is OfficeSession -> OfficePlaceholderView(session)
        }
    }
}
