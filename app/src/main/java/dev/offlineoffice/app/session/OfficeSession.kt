// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.content.Context
import android.content.res.Configuration
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.office.OfficeClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Word/Excel/PowerPoint session backed by the Office engine. The engine edits a copy of its
 * own in [workDir] in place; saving and recovery copies are taken from that copy after the
 * engine has saved it.
 */
class OfficeSession(
    override val tabId: String,
    override val kind: DocumentKind,
    private val context: Context,
    private val workDir: File,
) : DocumentSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Edited since the last save to the original. The engine's own flag also resets on recovery copies. */
    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** The engine edits this copy in place. */
    private lateinit var working: File

    lateinit var document: OfficeClient
        private set

    override suspend fun load(file: File, viewState: String?) {
        val ext = file.extension.ifEmpty { kind.extensions.first() }
        working = File(workDir, "document.$ext")
        withContext(Dispatchers.IO) {
            workDir.deleteRecursively()
            workDir.mkdirs()
            file.copyTo(working)
        }
        document = OfficeClient(context)
        scope.launch { document.modified.collect { if (it) _dirty.value = true } }
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        document.open(working, darkTheme = dark)
    }

    override fun captureViewState(): String? = null

    override suspend fun writeTo(target: File) {
        // The engine refuses to save an unmodified document; its file is then already current
        // (as loaded, or as of the engine's last save).
        if (document.modified.value) document.save()
        withContext(Dispatchers.IO) { working.copyTo(target, overwrite = true) }
    }

    override fun markSaved() {
        _dirty.value = false
    }

    override suspend fun close() {
        scope.cancel()
        try {
            if (::document.isInitialized) document.close()
        } finally {
            withContext(Dispatchers.IO) { workDir.deleteRecursively() }
        }
    }
}
