// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers

import androidx.compose.runtime.Composable
import dev.offlineoffice.app.session.OfficeSession
import dev.offlineoffice.app.ui.CenteredMessage

/** Shown until the LibreOfficeKit engine is integrated (milestone M4). */
@Composable
fun OfficePlaceholderView(session: OfficeSession) {
    CenteredMessage("Word, Excel and PowerPoint editing arrives with the Office engine (milestone M4).\n\nThis tab is kept open and saved like any other.")
}
