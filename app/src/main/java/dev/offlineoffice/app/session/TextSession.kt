// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import dev.offlineoffice.app.doc.DocumentKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plain-text session. M1 keeps this minimal (UTF-8, editable); M2 adds encoding and
 * line-ending preservation, large-file read-only mode, find/replace and Markdown preview.
 */
class TextSession(override val tabId: String, override val kind: DocumentKind) : DocumentSession {
    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty

    val state = TextFieldState()
    private var savedText: CharSequence = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalFoundationApi::class)
    override suspend fun load(file: File, viewState: String?) {
        val text = withContext(Dispatchers.IO) { file.readText() }
        withContext(Dispatchers.Main.immediate) {
            state.setTextAndPlaceCursorAtEnd(text)
            savedText = text
            viewState?.let {
                val json = JSONObject(it)
                val start = json.optInt("selStart").coerceIn(0, text.length)
                val end = json.optInt("selEnd").coerceIn(start, text.length)
                state.edit { selection = TextRange(start, end) }
            }
            state.undoState.clearHistory()
            scope.launch { snapshotFlow { state.text.toString() }.collect { _dirty.value = it != savedText.toString() } }
        }
    }

    override fun captureViewState(): String =
        JSONObject().put("selStart", state.selection.start).put("selEnd", state.selection.end).toString()

    override suspend fun writeTo(target: File) {
        val text = withContext(Dispatchers.Main.immediate) { state.text.toString() }
        withContext(Dispatchers.IO) { target.writeText(text) }
    }

    override fun markSaved() {
        savedText = state.text.toString()
        _dirty.value = false
    }

    override suspend fun close() = scope.cancel()
}
