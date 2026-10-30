// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.text.CharsetGuesser
import dev.offlineoffice.app.text.LineEnding
import dev.offlineoffice.app.text.LineIndex
import dev.offlineoffice.app.text.SizeClass
import dev.offlineoffice.app.text.TextCodec
import dev.offlineoffice.app.text.TextFormat
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plain-text session: Markdown, CSV and every other text kind.
 *
 * Files up to 2 MB are decoded into a [TextFieldState] and edited; the charset, BOM and line-ending
 * style are remembered (and persisted in the view state) so Save writes them back unchanged.
 * Larger files are never decoded as a whole: they open read-only over a streaming [LineIndex]
 * (the whole file up to 50 MB, a head or tail window beyond that).
 */
class TextSession(override val tabId: String, override val kind: DocumentKind) : DocumentSession {
    /** Whether the file is edited, shown read-only, or waiting for a head/tail choice. */
    sealed interface Mode {
        data object Editable : Mode
        data class ReadOnly(val sizeBytes: Long, val window: Window) : Mode
        data class Huge(val sizeBytes: Long) : Mode
    }

    enum class Window { WHOLE, HEAD, TAIL }

    /** What the document area shows. PREVIEW/SPLIT apply to Markdown, TABLE to CSV. */
    enum class Pane { TEXT, PREVIEW, SPLIT, TABLE }

    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty

    val state = TextFieldState()
    var mode: Mode by mutableStateOf(Mode.Editable)
        private set
    var format: TextFormat by mutableStateOf(TextFormat.DEFAULT)
        private set
    /** Some bytes were not valid in the detected charset and were replaced on decode. */
    var lossy: Boolean by mutableStateOf(false)
        private set
    var wrap: Boolean by mutableStateOf(true)
    var monospace: Boolean by mutableStateOf(true)
    var pane: Pane by mutableStateOf(Pane.TEXT)

    /** Read-only modes: the index once built, and build progress (0..1) meanwhile. */
    var index: LineIndex? by mutableStateOf(null)
        private set
    var indexProgress: Float by mutableFloatStateOf(0f)
        private set
    var indexError: String? by mutableStateOf(null)
        private set

    /** Scroll positions reported by the UI, persisted in the view state and restored once. */
    var editorScroll: Int = 0
    var firstVisibleLine: Int = 0

    /** File extension, lower case (decides JSON tools). */
    var extension: String = ""
        private set

    private var savedText: String by mutableStateOf("")
    private var savedFormat: TextFormat by mutableStateOf(TextFormat.DEFAULT)
    private var terminators: List<String>? = null
    private var source: File? = null
    private var bomLength = 0
    private var indexJob: Job? = null
    private val blockCache = object : LinkedHashMap<Int, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?) = size > CACHED_BLOCKS
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override suspend fun load(file: File, viewState: String?) {
        val json = viewState?.let { runCatching { JSONObject(it) }.getOrNull() }
        val hint = json?.optString("charset")?.takeIf { it.isNotEmpty() }?.let {
            TextFormat(
                charset = it,
                bom = json.optBoolean("bom"),
                lineEnding = runCatching { LineEnding.valueOf(json.optString("eol")) }.getOrDefault(LineEnding.LF),
            )
        }
        source = file
        extension = file.name.substringAfterLast('.', "").lowercase()
        val size = withContext(Dispatchers.IO) { file.length() }
        withContext(Dispatchers.Main.immediate) {
            json?.let {
                wrap = it.optBoolean("wrap", wrap)
                monospace = it.optBoolean("mono", monospace)
                pane = runCatching { Pane.valueOf(it.optString("pane")) }.getOrDefault(Pane.TEXT)
                editorScroll = it.optInt("scroll")
                firstVisibleLine = it.optInt("line")
            }
        }
        when (SizeClass.of(size)) {
            SizeClass.EDITABLE -> loadEditable(file, hint, json)
            SizeClass.READ_ONLY -> {
                detectLargeFormat(file, hint)
                mode = Mode.ReadOnly(size, Window.WHOLE)
                buildIndex(Window.WHOLE)
            }
            SizeClass.HUGE -> {
                detectLargeFormat(file, hint)
                val window = runCatching { Window.valueOf(json?.optString("window").orEmpty()) }.getOrNull()
                mode = Mode.Huge(size)
                if (window == Window.HEAD || window == Window.TAIL) openWindow(window)
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class) // UndoState.clearHistory
    private suspend fun loadEditable(file: File, hint: TextFormat?, json: JSONObject?) {
        val decoded = withContext(Dispatchers.IO) {
            TextCodec.decode(file.readBytes(), hint, CharsetGuesser::candidates)
        }
        withContext(Dispatchers.Main.immediate) {
            val text = decoded.text
            state.setTextAndPlaceCursorAtEnd(text)
            savedText = text
            terminators = decoded.terminators
            lossy = decoded.lossy
            format = decoded.format
            savedFormat = decoded.format
            mode = Mode.Editable
            json?.let {
                val start = it.optInt("selStart").coerceIn(0, text.length)
                val end = it.optInt("selEnd").coerceIn(start, text.length)
                state.edit { selection = TextRange(start, end) }
            } ?: state.edit { selection = TextRange(0) }
            state.undoState.clearHistory()
            scope.launch {
                snapshotFlow { format != savedFormat || !state.text.contentEquals(savedText) }
                    .collect { _dirty.value = it }
            }
        }
    }

    /** Detects charset, BOM and line endings from the first 64 KB of a file too large to decode. */
    private suspend fun detectLargeFormat(file: File, hint: TextFormat?) {
        val (fmt, bom) = withContext(Dispatchers.IO) {
            val sample = ByteArray(SAMPLE_BYTES)
            val n = FileInputStream(file).use { it.read(sample) }.coerceAtLeast(0)
            val bytes = sample.copyOf(n)
            val (name, bomLen, _) = TextCodec.detectCharset(bytes, complete = n < SAMPLE_BYTES, hint = hint, detector = CharsetGuesser::candidates)
            val text = String(bytes, bomLen, bytes.size - bomLen, Charset.forName(name))
            TextFormat(name, bomLen > 0, TextCodec.detectLineEnding(text)) to bomLen
        }
        format = fmt
        savedFormat = fmt
        bomLength = bom
    }

    /** For a file over 50 MB: shows its first or last [WINDOW_BYTES] read-only. */
    fun openWindow(window: Window) {
        val size = source?.length() ?: return
        mode = Mode.ReadOnly(size, window)
        buildIndex(window)
    }

    /** Returns to the head/tail choice of a huge file. */
    fun closeWindow() {
        val size = source?.length() ?: return
        indexJob?.cancel()
        index = null
        synchronized(blockCache) { blockCache.clear() }
        mode = Mode.Huge(size)
    }

    private fun buildIndex(window: Window) {
        val file = source ?: return
        indexJob?.cancel()
        index = null
        indexError = null
        indexProgress = 0f
        synchronized(blockCache) { blockCache.clear() }
        val charset = Charset.forName(format.charset)
        val width = LineIndex.CodeUnit.of(charset).width
        indexJob = scope.launch {
            try {
                val built = withContext(Dispatchers.IO) {
                    val size = file.length()
                    val (start, end) = when (window) {
                        Window.WHOLE -> bomLength.toLong() to size
                        Window.HEAD -> bomLength.toLong() to minOf(size, bomLength + WINDOW_BYTES)
                        Window.TAIL -> {
                            val raw = maxOf(bomLength.toLong(), size - WINDOW_BYTES)
                            (raw - (raw - bomLength) % width) to size
                        }
                    }
                    LineIndex.build(
                        file, charset, start, end,
                        skipPartialFirstLine = window == Window.TAIL && start > bomLength,
                        onProgress = { p -> scope.launch { indexProgress = p } },
                        isCancelled = { !isActive },
                    )
                }
                index = built
            } catch (e: Exception) {
                ensureActive()
                indexError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Line [line] of the read-only view (blocks are read from disk on demand and cached). */
    fun line(line: Int): String {
        val idx = index ?: return ""
        val block = line / LineIndex.BLOCK
        val lines = synchronized(blockCache) {
            blockCache[block] ?: runCatching { idx.readBlock(block) }.getOrElse { listOf("<${it.message}>") }
                .also { blockCache[block] = it }
        }
        return lines.getOrElse(line % LineIndex.BLOCK) { "" }
    }

    /**
     * Finds the lines of the read-only view that contain [query], up to [limit] of them.
     * Runs on a background thread; [onProgress] gets the fraction scanned.
     */
    suspend fun searchLines(query: String, matchCase: Boolean, limit: Int = 10_000, onProgress: (Float) -> Unit = {}): List<Int> {
        val idx = index ?: return emptyList()
        if (query.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val hits = ArrayList<Int>()
            for (block in 0 until idx.blockCount) {
                ensureActive()
                val lines = idx.readBlock(block)
                lines.forEachIndexed { i, text ->
                    if (hits.size < limit && text.contains(query, ignoreCase = !matchCase)) hits += block * LineIndex.BLOCK + i
                }
                if (hits.size >= limit) break
                if (block % 64 == 0) onProgress(block.toFloat() / idx.blockCount)
            }
            hits
        }
    }

    val isJson: Boolean get() = extension == "json" || extension == "geojson"

    /** Changes the encoding/line endings used on the next save (an explicit, user-chosen conversion). */
    fun changeFormat(newFormat: TextFormat) {
        if (mode != Mode.Editable) return
        format = newFormat
    }

    /** Replaces the whole text as one undoable step (JSON Format, Replace all). */
    fun replaceAll(text: String) = programmaticEdit { replace(0, length, text) }

    /** Replaces [start, end) and puts the cursor after it, as one undoable step (Replace). */
    fun replaceRange(start: Int, end: Int, text: String) = programmaticEdit {
        replace(start, end, text)
        selection = TextRange(start + text.length)
    }

    // TextFieldState.edit {} clears the field's undo history, so programmatic edits keep a small
    // stack of the previous texts. Undo walks the field's own history (typing since the last
    // programmatic edit) first, then this stack.
    private val programmaticUndo = ArrayDeque<String>()
    private var programmaticUndoSize by mutableIntStateOf(0)

    private fun programmaticEdit(block: TextFieldBuffer.() -> Unit) {
        programmaticUndo.addLast(state.text.toString())
        while (programmaticUndo.size > MAX_PROGRAMMATIC_UNDO) programmaticUndo.removeFirst()
        programmaticUndoSize = programmaticUndo.size
        state.edit(block)
    }

    @OptIn(ExperimentalFoundationApi::class)
    val canUndo: Boolean get() = state.undoState.canUndo || programmaticUndoSize > 0

    @OptIn(ExperimentalFoundationApi::class)
    val canRedo: Boolean get() = state.undoState.canRedo

    @OptIn(ExperimentalFoundationApi::class)
    fun undo() {
        if (state.undoState.canUndo) {
            state.undoState.undo()
            return
        }
        val previous = programmaticUndo.removeLastOrNull() ?: return
        programmaticUndoSize = programmaticUndo.size
        state.edit {
            replace(0, length, previous)
            selection = TextRange(0)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    fun redo() = state.undoState.redo()

    override fun captureViewState(): String = JSONObject()
        .put("selStart", state.selection.start)
        .put("selEnd", state.selection.end)
        .put("charset", format.charset)
        .put("bom", format.bom)
        .put("eol", format.lineEnding.name)
        .put("wrap", wrap)
        .put("mono", monospace)
        .put("pane", pane.name)
        .put("scroll", editorScroll)
        .put("line", firstVisibleLine)
        .apply { (mode as? Mode.ReadOnly)?.window?.let { put("window", it.name) } }
        .toString()

    override suspend fun writeTo(target: File) {
        if (mode != Mode.Editable) {
            // Read-only views never change the document: write the original bytes.
            val file = checkNotNull(source) { "Document is not loaded" }
            withContext(Dispatchers.IO) { file.copyTo(target, overwrite = true) }
            return
        }
        val (text, fmt) = withContext(Dispatchers.Main.immediate) { state.text.toString() to format }
        val bytes = withContext(Dispatchers.Default) { TextCodec.encode(text, fmt, terminators) }
        withContext(Dispatchers.IO) { target.writeBytes(bytes) }
    }

    override fun markSaved() {
        savedText = state.text.toString()
        savedFormat = format
        _dirty.value = false
    }

    override suspend fun close() {
        indexJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val SAMPLE_BYTES = 64 * 1024
        const val WINDOW_BYTES = 4L * 1024 * 1024
        const val CACHED_BLOCKS = 48
        const val MAX_PROGRAMMATIC_UNDO = 5
    }
}
