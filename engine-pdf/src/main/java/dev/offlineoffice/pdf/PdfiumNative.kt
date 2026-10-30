// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.pdf

import android.graphics.Bitmap

/**
 * Raw JNI surface over PDFium. Use [PdfDocument] instead of calling this directly.
 * Coordinates are display points: PDF points from the top-left of the page as shown.
 */
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

    @JvmStatic external fun formType(handle: Long): Int

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

    /** JSON array describing the page's form widgets. */
    @JvmStatic external fun pageFields(handle: Long, index: Int): String

    @JvmStatic external fun setFieldText(handle: Long, index: Int, annotIndex: Int, text: String): Boolean

    @JvmStatic external fun setFieldChoice(handle: Long, index: Int, annotIndex: Int, option: Int, selected: Boolean): Boolean

    @JvmStatic external fun clickField(handle: Long, index: Int, annotIndex: Int): Boolean

    /** [matchCount, then per match: rectCount, l, t, r, b, ...]. */
    @JvmStatic external fun search(handle: Long, index: Int, query: String, matchCase: Boolean, wholeWord: Boolean): FloatArray

    @JvmStatic external fun insertImage(
        handle: Long,
        index: Int,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        asAnnotation: Boolean,
    ): Boolean

    @JvmStatic external fun insertText(
        handle: Long,
        index: Int,
        text: String,
        x: Float,
        baseline: Float,
        fontSize: Float,
        color: Int,
    ): Boolean

    @JvmStatic external fun insertPath(handle: Long, index: Int, points: FloatArray, width: Float, color: Int): Boolean

    /** Writes a copy of the document to [fd] (not closed). */
    @JvmStatic external fun save(handle: Long, fd: Int, incremental: Boolean): Boolean
}
