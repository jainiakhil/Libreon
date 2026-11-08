// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.text.WordCounter
import dev.offlineoffice.pdf.AnnotType
import dev.offlineoffice.pdf.FormField
import dev.offlineoffice.pdf.FormType
import dev.offlineoffice.pdf.Highlight
import dev.offlineoffice.pdf.PageRect
import dev.offlineoffice.pdf.PageSize
import dev.offlineoffice.pdf.PdfDocument
import dev.offlineoffice.pdf.PdfOpenException
import dev.offlineoffice.pdf.SearchMatch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * PDF session: viewing, AcroForm filling, flat-form marks and signatures, saved through PDFium.
 *
 * The loaded file is first copied to a private snapshot that is unlinked once PDFium holds its
 * descriptor, so nothing (e.g. the TabManager refreshing the working copy after a save) can
 * change the bytes PDFium reads lazily, and incremental saves can always append to them.
 */
class PdfSession(override val tabId: String) : DocumentSession, SaveAdvice, DocumentTools {
    override val kind = DocumentKind.PDF

    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** PDFium is single-threaded per document; all calls go through this thread. */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf-$tabId") }
    private val thread = executor.asCoroutineDispatcher()
    private var document: PdfDocument? = null

    /** The snapshot waiting for a password; deleted once opened. */
    private var lockedSnapshot: File? = null

    /** Page sizes in display points; snapshot state, so viewers follow page edits. */
    var pageSizes: List<PageSize> by mutableStateOf(emptyList())
        private set
    var formType: FormType = FormType.NONE
        private set

    /** True while the document waits for its password (see [unlock]). */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Bumped after every edit; renders and field lists keyed on it go stale. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Placed but not yet committed marks (survive the viewer leaving composition). */
    private val _marks = MutableStateFlow<List<PdfMark>>(emptyList())
    val marks: StateFlow<List<PdfMark>> = _marks.asStateFlow()
    private val markIds = AtomicLong(1)

    /** A signature was committed since the last save: Save should default to a new file. */
    private val _signed = MutableStateFlow(false)
    val signed: StateFlow<Boolean> = _signed.asStateFlow()

    /** Page content was rewritten since load, so saves must be full rather than incremental. */
    private var contentEdited = false

    /** The source ends in a classic xref table, so PDFium's incremental output is well-formed. */
    private var incrementalSafe = false

    /** Restored position, consumed once by the viewer. */
    var initialPage = 0
        private set
    var initialOffset = 0
        private set
    var initialZoom = 1f
        private set

    /** Updated by the viewer as the user scrolls and zooms. */
    var currentPage = 0
    var currentOffset = 0
    var currentZoom = 1f

    private val fieldCache = HashMap<Int, Pair<Int, List<FormField>>>()
    private val textCache = HashMap<Int, Pair<Int, String>>()
    private val highlightCache = HashMap<Int, Pair<Int, List<Highlight>>>()
    private val renderCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    override suspend fun load(file: File, viewState: String?) = withContext(thread) {
        val snapshot = File(file.parentFile, ".pdf-open-${System.nanoTime()}.pdf")
        file.copyTo(snapshot, overwrite = true)
        incrementalSafe = PdfXref.endsWithClassicXref(snapshot)
        viewState?.let {
            val json = JSONObject(it)
            initialPage = json.optInt("page")
            initialOffset = json.optInt("offset")
            initialZoom = json.optDouble("zoom", 1.0).toFloat().coerceIn(MIN_ZOOM, MAX_ZOOM)
            _signed.value = json.optBoolean("signed")
            contentEdited = false
        }
        try {
            openSnapshot(snapshot, null)
        } catch (e: PdfOpenException) {
            if (!e.needsPassword) {
                snapshot.delete()
                throw e
            }
            lockedSnapshot = snapshot
            _locked.value = true
        }
    }

    /** Tries [password] on a locked document. Returns false if it was wrong. */
    suspend fun unlock(password: String): Boolean = withContext(thread) {
        val snapshot = lockedSnapshot ?: return@withContext true
        try {
            openSnapshot(snapshot, password)
            lockedSnapshot = null
            _locked.value = false
            true
        } catch (e: PdfOpenException) {
            if (!e.needsPassword) throw e
            false
        }
    }

    private fun openSnapshot(snapshot: File, password: String?) {
        val doc = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfDocument.open(it, password) }
        snapshot.delete() // PDFium keeps its own descriptor; the data lives until close.
        document = doc
        pageSizes = (0 until doc.pageCount).map(doc::pageSize)
        formType = doc.formType
        initialPage = initialPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
        currentPage = initialPage
        currentOffset = initialOffset
        currentZoom = initialZoom
    }

    // --- rendering ---

    /** Whole page at [widthPx]; cached until the next edit. */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap {
        val key = "$index:${_revision.value}:$widthPx"
        renderCache.get(key)?.let { return it }
        return withContext(thread) {
            val doc = checkNotNull(document) { "session closed" }
            val size = pageSizes[index]
            val heightPx = (widthPx * size.height / size.width).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { doc.render(index, it) }
        }.also { renderCache.put(key, it) }
    }

    /**
     * The part ([left], [top], [width] x [height] pixels) of page [index] drawn at
     * [pageWidthPx] x [pageHeightPx]. Used for sharp detail when zoomed in.
     */
    suspend fun renderRegion(index: Int, pageWidthPx: Int, pageHeightPx: Int, left: Int, top: Int, width: Int, height: Int): Bitmap =
        withContext(thread) {
            val doc = checkNotNull(document) { "session closed" }
            Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { doc.render(index, it, -left, -top, pageWidthPx, pageHeightPx) }
        }

    // --- search ---

    /** Searches every page in order, reporting each page's matches to [onPage] as found. */
    suspend fun search(query: String, onPage: (List<SearchMatch>) -> Unit) {
        if (query.isBlank()) return
        for (index in pageSizes.indices) {
            val matches = withContext(thread) { document?.search(index, query) ?: emptyList() }
            if (matches.isNotEmpty()) onPage(matches)
        }
    }

    // --- text, selection and highlights ---

    /** Page [index]'s text (string index = character index), cached per revision. */
    suspend fun pageText(index: Int): String {
        val rev = _revision.value
        textCache[index]?.takeIf { it.first == rev }?.let { return it.second }
        return withContext(thread) { document?.pageText(index).orEmpty() }.also { textCache[index] = rev to it }
    }

    /** The character under (x, y) display points on page [index], or -1. */
    suspend fun charIndexAt(index: Int, x: Float, y: Float, tolerance: Float): Int =
        withContext(thread) { document?.charIndexAt(index, x, y, tolerance) ?: -1 }

    suspend fun textRects(index: Int, start: Int, end: Int): List<PageRect> =
        if (end <= start) emptyList() else withContext(thread) { document?.textRects(index, start, end) ?: emptyList() }

    /** Highlight annotations on page [index], cached per revision. */
    suspend fun highlights(index: Int): List<Highlight> {
        val rev = _revision.value
        highlightCache[index]?.takeIf { it.first == rev }?.let { return it.second }
        return withContext(thread) { document?.highlights(index) ?: emptyList() }.also { highlightCache[index] = rev to it }
    }

    /** Marks characters [start, end) of page [index] with a highlight (or underline, strike-out…). */
    suspend fun highlight(index: Int, start: Int, end: Int, argb: Int, type: AnnotType = AnnotType.HIGHLIGHT): Boolean {
        val text = pageText(index).let { it.substring(start.coerceIn(0, it.length), end.coerceIn(0, it.length)) }
        return edit { doc ->
            val rects = doc.textRects(index, start, end)
            rects.isNotEmpty() && doc.addHighlight(index, rects, argb, text.trim(), type)
        }
    }

    suspend fun removeHighlight(highlight: Highlight): Boolean = edit { it.removeAnnotation(highlight.page, highlight.annotIndex) }

    /** The text of a note (or any annotation). */
    suspend fun annotationText(highlight: Highlight): String =
        withContext(thread) { document?.annotationText(highlight.page, highlight.annotIndex).orEmpty() }

    // --- annotations ---

    /** Adds a freehand drawing of [strokes] ([x, y, ...] display points each) to page [index]. */
    suspend fun addInk(index: Int, strokes: List<FloatArray>, argb: Int, width: Float) = edit { it.addInk(index, strokes, argb, width) }

    suspend fun addShape(index: Int, rect: PageRect, ellipse: Boolean, argb: Int, width: Float) =
        edit { it.addShape(index, rect, ellipse, argb, width) }

    suspend fun addNote(index: Int, x: Float, y: Float, text: String, argb: Int) = edit { it.addNote(index, x, y, text, argb) }

    // --- pages ---

    suspend fun deletePages(indices: List<Int>) = editPages { it.deletePages(indices) }

    suspend fun rotatePages(indices: List<Int>, quarterTurns: Int) = editPages { it.rotatePages(indices, quarterTurns) }

    suspend fun movePages(indices: List<Int>, destIndex: Int) = editPages { it.movePages(indices, destIndex) }

    /** Inserts every page of the PDF in [source] before page [insertAt]. Throws [PdfOpenException]. */
    suspend fun insertPdf(source: ParcelFileDescriptor, insertAt: Int): Boolean = editPages { it.importPages(source, insertAt) > 0 }

    /** Writes the pages at [indices] as a new PDF to [target]; the document itself is unchanged. */
    suspend fun extractPages(indices: List<Int>, target: File) = withContext(thread) {
        checkNotNull(document) { "session closed" }.extractPages(indices, target)
    }

    /** A page-structure edit: the page list is reread, pending marks go and the next save is full. */
    private suspend fun editPages(block: (PdfDocument) -> Boolean): Boolean {
        val ok = edit(block)
        if (ok) {
            withContext(thread) { document?.let { doc -> pageSizes = (0 until doc.pageCount).map(doc::pageSize) } }
            contentEdited = true
            _marks.value = emptyList()
            currentPage = currentPage.coerceIn(0, (pageSizes.size - 1).coerceAtLeast(0))
        }
        return ok
    }

    /** Text the viewer has selected, for the word count. */
     var selectedText: String? = null

    // --- DocumentTools ---

    override val findRequests = findRequestFlow()

    override suspend fun wordCount(): WordCount {
        if (document == null) return WordCount(null, null, "Unlock the document to count its words.")
        var words = 0
        var chars = 0
        for (index in pageSizes.indices) {
            val text = pageText(index)
            withContext(Dispatchers.Default) {
                words += WordCounter.count(text)
                chars += WordCounter.countChars(text)
            }
        }
        val selection = selectedText?.takeIf { it.isNotBlank() }?.let { Count(WordCounter.count(it), WordCounter.countChars(it)) }
        val note = if (words == 0 && pageSizes.isNotEmpty()) "No text was found: this PDF may be scanned images." else null
        return WordCount(Count(words, chars), selection, note)
    }

    // --- forms ---

    /** Form widgets on page [index], cached per revision. */
    suspend fun fields(index: Int): List<FormField> {
        if (formType == FormType.NONE) return emptyList()
        val rev = _revision.value
        fieldCache[index]?.takeIf { it.first == rev }?.let { return it.second }
        return withContext(thread) { document?.formFields(index) ?: emptyList() }
            .also { fieldCache[index] = rev to it }
    }

    suspend fun setText(field: FormField, text: String) = edit { it.setText(field, text) }

    suspend fun selectOption(field: FormField, option: Int, selected: Boolean = true) = edit { it.selectOption(field, option, selected) }

    suspend fun click(field: FormField) = edit { it.click(field) }

    // --- marks ---

    fun nextMarkId(): Long = markIds.getAndIncrement()

    fun putMark(mark: PdfMark) = _marks.update { list -> list.filterNot { it.id == mark.id } + mark }

    fun removeMark(id: Long) = _marks.update { list -> list.filterNot { it.id == id } }

    /** Writes [mark] into the document and drops it from the pending list. */
    suspend fun commit(mark: PdfMark): Boolean {
        val ok = edit { doc ->
            when (mark) {
                is PdfMark.Text -> doc.insertText(
                    mark.page, mark.text, mark.rect.left, MarkGeometry.baselineFor(mark.rect),
                    MarkGeometry.fontSizeFor(mark.rect), mark.argb,
                )
                is PdfMark.Symbol -> doc.insertPath(
                    mark.page, MarkGeometry.symbolPoints(mark.rect, mark.cross), MarkGeometry.symbolStroke(mark.rect), mark.argb,
                )
                is PdfMark.Signature -> doc.insertImage(mark.page, mark.image, mark.rect, mark.asAnnotation)
            }
        }
        if (ok) {
            if (mark !is PdfMark.Signature || !mark.asAnnotation) contentEdited = true
            if (mark is PdfMark.Signature) _signed.value = true
            removeMark(mark.id)
        }
        return ok
    }

    private suspend fun edit(block: (PdfDocument) -> Boolean): Boolean {
        val ok = withContext(thread) { document?.let(block) ?: false }
        if (ok) {
            fieldCache.clear()
            textCache.clear()
            highlightCache.clear()
            renderCache.evictAll()
            _revision.update { it + 1 }
            _dirty.value = true
        }
        return ok
    }

    // --- DocumentSession ---

    override fun captureViewState(): String = JSONObject()
        .put("page", currentPage)
        .put("offset", currentOffset)
        .put("zoom", currentZoom.toDouble())
        .put("signed", _signed.value)
        .toString()

    override suspend fun writeTo(target: File) = withContext(thread) {
        val doc = document
        if (doc == null) {
            // Still locked: nothing can have changed, so the snapshot is the document.
            checkNotNull(lockedSnapshot) { "session closed" }.copyTo(target, overwrite = true)
            return@withContext
        }
        doc.saveTo(target, incremental = !contentEdited && incrementalSafe)
    }

    override fun markSaved() {
        _dirty.value = false
        _signed.value = false
    }

    override val prefersSaveAsNew: Boolean get() = _signed.value

    override fun suggestedSaveName(displayName: String): String {
        val base = displayName.removeSuffix(".pdf").removeSuffix(".PDF").trimEnd().removeSuffix(" (signed)")
        return "$base (signed).pdf"
    }

    override suspend fun close() {
        withContext(thread) {
            document?.close()
            document = null
            lockedSnapshot?.delete()
            lockedSnapshot = null
            renderCache.evictAll()
        }
        executor.shutdown()
    }

    companion object {
        /** Hard bounds on the zoom (1 = page width fits the screen); the viewer narrows them. */
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 100f
    }
}
