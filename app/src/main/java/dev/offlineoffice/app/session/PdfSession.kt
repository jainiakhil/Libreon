// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.LruCache
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.pdf.FormField
import dev.offlineoffice.pdf.FormType
import dev.offlineoffice.pdf.PageSize
import dev.offlineoffice.pdf.PdfDocument
import dev.offlineoffice.pdf.PdfOpenException
import dev.offlineoffice.pdf.SearchMatch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
class PdfSession(override val tabId: String) : DocumentSession, SaveAdvice {
    override val kind = DocumentKind.PDF

    private val _dirty = MutableStateFlow(false)
    override val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** PDFium is single-threaded per document; all calls go through this thread. */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf-$tabId") }
    private val thread = executor.asCoroutineDispatcher()
    private var document: PdfDocument? = null

    /** The snapshot waiting for a password; deleted once opened. */
    private var lockedSnapshot: File? = null

    var pageSizes: List<PageSize> = emptyList()
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
    private val renderCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    override suspend fun load(file: File, viewState: String?) = withContext(thread) {
        val snapshot = File(file.parentFile, ".pdf-open-${System.nanoTime()}.pdf")
        file.copyTo(snapshot, overwrite = true)
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
        doc.saveTo(target, incremental = !contentEdited)
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
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
