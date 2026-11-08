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

    /** Takes ownership of [fd]. Returns a handle, or a small negative PDFium error code (handles may also be negative: tagged pointers). */
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

    /** The page's text; string index = PDFium character index. */
    @JvmStatic external fun pageText(handle: Long, index: Int): String

    /** Character index nearest (x, y) within [tolerance] points, or -1. */
    @JvmStatic external fun charIndexAt(handle: Long, index: Int, x: Float, y: Float, tolerance: Float): Int

    /** [l, t, r, b, ...] covering [count] characters from [start]. */
    @JvmStatic external fun textRects(handle: Long, index: Int, start: Int, count: Int): FloatArray

    /** Text markup of PDFium [subtype] (highlight, underline, strike-out or squiggly). */
    @JvmStatic external fun addHighlight(handle: Long, index: Int, subtype: Int, rects: FloatArray, color: Int, contents: String): Boolean

    /** [count, then per annotation: annotIndex, subtype, red, green, blue, rectCount, l, t, r, b, ...]. */
    @JvmStatic external fun highlights(handle: Long, index: Int): FloatArray

    @JvmStatic external fun annotContents(handle: Long, index: Int, annotIndex: Int): String?

    /** "U<uri>", "P<page> <top>" or null for the link at display point (x, y). */
    @JvmStatic external fun linkAt(handle: Long, index: Int, x: Float, y: Float): String?

    @JvmStatic external fun removeAnnot(handle: Long, index: Int, annotIndex: Int): Boolean

    /** [strokes] = [pointCount, x, y, ..., pointCount, ...] in display points. */
    @JvmStatic external fun addInk(handle: Long, index: Int, strokes: FloatArray, color: Int, width: Float): Boolean

    @JvmStatic external fun addShape(handle: Long, index: Int, subtype: Int, left: Float, top: Float, right: Float, bottom: Float, color: Int, width: Float): Boolean

    @JvmStatic external fun addNote(handle: Long, index: Int, x: Float, y: Float, contents: String, color: Int): Boolean

    @JvmStatic external fun deletePages(handle: Long, indices: IntArray): Boolean

    @JvmStatic external fun rotatePages(handle: Long, indices: IntArray, quarterTurns: Int): Boolean

    @JvmStatic external fun movePages(handle: Long, indices: IntArray, destIndex: Int): Boolean

    /** Pages inserted, or a negative PDFium error. [srcFd] is not closed. */
    @JvmStatic external fun importPages(handle: Long, srcFd: Int, length: Long, insertAt: Int): Int

    /** Writes a new PDF of the pages at [indices] to [fd] (not closed). */
    @JvmStatic external fun extractPages(handle: Long, indices: IntArray, fd: Int): Boolean

    /** Writes a copy of the document to [fd] (not closed). */
    @JvmStatic external fun save(handle: Long, fd: Int, incremental: Boolean): Boolean
}
