// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import dev.offlineoffice.app.doc.DocumentKind
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Word/Excel/PowerPoint session. Placeholder until the LibreOfficeKit engine is integrated
 * (milestone M4): it keeps the file so tabs, recents and saving can be exercised end to end.
 */
class OfficeSession(override val tabId: String, override val kind: DocumentKind) : DocumentSession {
    override val dirty: StateFlow<Boolean> = MutableStateFlow(false)
    private var source: File? = null

    override suspend fun load(file: File, viewState: String?) {
        source = file
    }

    override fun captureViewState(): String? = null

    override suspend fun writeTo(target: File) {
        checkNotNull(source).copyTo(target, overwrite = true)
    }

    override fun markSaved() = Unit

    override suspend fun close() = Unit
}
