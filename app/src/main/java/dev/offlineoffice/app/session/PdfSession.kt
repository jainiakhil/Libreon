// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.pdf.PageSize
import dev.offlineoffice.pdf.PdfDocument
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** PDF viewing session (M1: read-only; forms and signatures arrive in M3). */
class PdfSession(override val tabId: String) : DocumentSession {
    override val kind = DocumentKind.PDF
    override val dirty: StateFlow<Boolean> = MutableStateFlow(false)

    /** PDFium is single-threaded per document; all calls go through this thread. */
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "pdf-$tabId") }
    private val thread = executor.asCoroutineDispatcher()
    private var document: PdfDocument? = null
    private var source: File? = null

    var pageSizes: List<PageSize> = emptyList()
        private set

    /** Restored scroll position, consumed once by the viewer. */
    var initialPage = 0
        private set
    var initialOffset = 0
        private set

    /** Updated by the viewer as the user scrolls. */
    var currentPage = 0
    var currentOffset = 0

    override suspend fun load(file: File, viewState: String?) = withContext(thread) {
        source = file
        val doc = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfDocument.open(it) }
        document = doc
        pageSizes = (0 until doc.pageCount).map(doc::pageSize)
        viewState?.let {
            val json = JSONObject(it)
            initialPage = json.optInt("page").coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0))
            initialOffset = json.optInt("offset")
        }
        currentPage = initialPage
        currentOffset = initialOffset
    }

    suspend fun renderPage(index: Int, widthPx: Int): Bitmap = withContext(thread) {
        val doc = checkNotNull(document) { "session closed" }
        val size = pageSizes[index]
        val heightPx = (widthPx * size.height / size.width).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { doc.render(index, it) }
    }

    override fun captureViewState(): String =
        JSONObject().put("page", currentPage).put("offset", currentOffset).toString()

    override suspend fun writeTo(target: File) {
        // Nothing is editable yet, so the current document is the loaded file.
        withContext(thread) { checkNotNull(source).copyTo(target, overwrite = true) }
    }

    override fun markSaved() = Unit

    override suspend fun close() {
        withContext(thread) {
            document?.close()
            document = null
        }
        executor.shutdown()
    }
}
