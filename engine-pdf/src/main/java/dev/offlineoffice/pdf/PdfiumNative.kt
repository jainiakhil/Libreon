// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.pdf

import android.graphics.Bitmap

/** Raw JNI surface over PDFium. Use [PdfDocument] instead of calling this directly. */
internal object PdfiumNative {
    init {
        System.loadLibrary("pdfium")
        System.loadLibrary("oopdf")
        init()
    }

    @JvmStatic external fun init()

    /** Takes ownership of [fd]. Returns a handle, or a negative PDFium error code. */
    @JvmStatic external fun open(fd: Int, length: Long, password: String?): Long

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun pageCount(handle: Long): Int

    @JvmStatic external fun pageSize(handle: Long, index: Int, out: FloatArray): Boolean

    @JvmStatic external fun renderPage(
        handle: Long,
        index: Int,
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        drawAnnots: Boolean,
    )
}
