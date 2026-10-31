// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.content.Context
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.text.WordCounter
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
    /** Whether the page should open in the dark theme. */
    private val darkTheme: () -> Boolean,
) : DocumentSession, DocumentTools {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Edited since the last save to the original. The engine's own flag also resets on recovery copies. */
    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** The engine edits this copy in place. */
    private lateinit var working: File

    lateinit var document: OfficeClient
        private set

    /** Formats the engine can import but not write back (Apple iWork, WordPerfect…) open read-only. */
    val readOnly: Boolean get() = DocumentKind.isImportOnly(working.name)

    override val findRequests = findRequestFlow()

    /** Writer's last document totals: its line shows only the selection while text is selected. */
    private var lastTotals: Count? = null

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
        scope.launch { document.wordCount.collect { line -> line?.let(WriterWordCount::parse)?.let { lastTotals = it } } }
        document.open(working, darkTheme = darkTheme(), readOnly = readOnly)
    }

    override fun captureViewState(): String? = null

    override suspend fun wordCount(): WordCount {
        if (document.state.value != OfficeClient.State.Ready) return WordCount(null, null, "The document is still opening.")
        val selected = runCatching { document.selectedText() }.getOrNull()
        val selection = selected?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.Default) { Count(WordCounter.count(it), WordCounter.countChars(it)) } }
        val total = if (kind == DocumentKind.WORD) lastTotals else null
        val note = when {
            selected == null -> "The selection could not be read."
            total == null && kind == DocumentKind.WORD -> "Document totals appear once the document has finished laying out."
            total == null -> "Totals are counted for text documents. Select cells or text to count them."
            else -> null
        }
        return WordCount(total, selection, note)
    }

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
