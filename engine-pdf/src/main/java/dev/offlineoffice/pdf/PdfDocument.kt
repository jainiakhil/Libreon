// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Page size in PDF points (1/72 inch). */
data class PageSize(val width: Float, val height: Float)

class PdfOpenException(val errorCode: Int) : Exception("PDFium error $errorCode") {
    val needsPassword: Boolean get() = errorCode == ERR_PASSWORD

    companion object {
        const val ERR_UNKNOWN = 1
        const val ERR_FILE = 2
        const val ERR_FORMAT = 3
        const val ERR_PASSWORD = 4
        const val ERR_SECURITY = 5
    }
}

/**
 * An open PDF. Not thread-safe: confine each instance to a single worker thread.
 */
class PdfDocument private constructor(private var handle: Long) : Closeable {

    val pageCount: Int = PdfiumNative.pageCount(handle)

    fun pageSize(index: Int): PageSize {
        val out = FloatArray(2)
        check(PdfiumNative.pageSize(checkOpen(), index, out)) { "bad page index $index" }
        return PageSize(out[0], out[1])
    }

    /**
     * Draws page [index] into [bitmap] (ARGB_8888). The page occupies the rectangle
     * ([left], [top], [width] x [height]) in bitmap pixels; pass negative offsets to draw a tile.
     */
    fun render(
        index: Int,
        bitmap: Bitmap,
        left: Int = 0,
        top: Int = 0,
        width: Int = bitmap.width,
        height: Int = bitmap.height,
        drawAnnotations: Boolean = true,
    ) {
        PdfiumNative.renderPage(checkOpen(), index, bitmap, left, top, width, height, drawAnnotations)
    }

    override fun close() {
        if (handle != 0L) {
            PdfiumNative.close(handle)
            handle = 0L
        }
    }

    private fun checkOpen(): Long {
        check(handle != 0L) { "document is closed" }
        return handle
    }

    companion object {
        /** Opens [pfd]; the descriptor is duplicated, so the caller keeps ownership of [pfd]. */
        fun open(pfd: ParcelFileDescriptor, password: String? = null): PdfDocument {
            val length = pfd.statSize
            val fd = pfd.dup().detachFd()
            val result = PdfiumNative.open(fd, length, password)
            if (result < 0) throw PdfOpenException((-result).toInt())
            return PdfDocument(result)
        }
    }
}
