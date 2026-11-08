// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

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
 * An open PDF with its interactive form environment. Not thread-safe: confine each instance
 * to a single worker thread. Rectangles and points are display points (see [PageRect]).
 */
class PdfDocument private constructor(private var handle: Long) : Closeable {

    /** Read from PDFium each time: page edits change it. */
    val pageCount: Int get() = PdfiumNative.pageCount(checkOpen())

    val formType: FormType = FormType.entries.getOrElse(PdfiumNative.formType(handle)) { FormType.NONE }

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

    /** The fillable widgets on page [index] with their current values. */
    fun formFields(index: Int): List<FormField> = FormField.parse(index, PdfiumNative.pageFields(checkOpen(), index))

    fun setText(field: FormField, text: String): Boolean =
        PdfiumNative.setFieldText(checkOpen(), field.page, field.annotIndex, text)

    fun selectOption(field: FormField, option: Int, selected: Boolean = true): Boolean =
        PdfiumNative.setFieldChoice(checkOpen(), field.page, field.annotIndex, option, selected)

    /** Toggles a check box or selects a radio button, as a click would. */
    fun click(field: FormField): Boolean = PdfiumNative.clickField(checkOpen(), field.page, field.annotIndex)

    fun search(index: Int, query: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<SearchMatch> {
        val raw = PdfiumNative.search(checkOpen(), index, query, matchCase, wholeWord)
        val matches = ArrayList<SearchMatch>(raw[0].toInt())
        var p = 1
        repeat(raw[0].toInt()) {
            val count = raw[p++].toInt()
            val rects = List(count) { PageRect(raw[p + it * 4], raw[p + it * 4 + 1], raw[p + it * 4 + 2], raw[p + it * 4 + 3]) }
            p += count * 4
            matches += SearchMatch(index, rects)
        }
        return matches
    }

    /** Page [index]'s text; string index = character index used by [charIndexAt] and [textRects]. */
    fun pageText(index: Int): String = PdfiumNative.pageText(checkOpen(), index)

    /** The character at (x, y) display points, within [tolerance] points; -1 if none. */
    fun charIndexAt(index: Int, x: Float, y: Float, tolerance: Float = 4f): Int =
        PdfiumNative.charIndexAt(checkOpen(), index, x, y, tolerance)

    /** Rectangles covering characters [start, end), one per text run. */
    fun textRects(index: Int, start: Int, end: Int): List<PageRect> =
        PdfiumNative.textRects(checkOpen(), index, start, end - start).toRects(0)

    /**
     * Adds text markup of [type] (highlight, underline, squiggly or strike-out) over [rects] in
     * [argb], recording [text] as its contents.
     */
    fun addHighlight(index: Int, rects: List<PageRect>, argb: Int, text: String, type: AnnotType = AnnotType.HIGHLIGHT): Boolean {
        val flat = FloatArray(rects.size * 4)
        rects.forEachIndexed { i, r ->
            flat[i * 4] = r.left; flat[i * 4 + 1] = r.top; flat[i * 4 + 2] = r.right; flat[i * 4 + 3] = r.bottom
        }
        return PdfiumNative.addHighlight(checkOpen(), index, type.code, flat, argb, text)
    }

    /** The markup annotations (highlights, drawings, shapes, notes…) on page [index]. */
    fun highlights(index: Int): List<Highlight> {
        val raw = PdfiumNative.highlights(checkOpen(), index)
        val out = ArrayList<Highlight>(raw[0].toInt())
        var p = 1
        repeat(raw[0].toInt()) {
            val annot = raw[p].toInt()
            val type = AnnotType.of(raw[p + 1].toInt())
            val argb = (0xFF shl 24) or (raw[p + 2].toInt() shl 16) or (raw[p + 3].toInt() shl 8) or raw[p + 4].toInt()
            val count = raw[p + 5].toInt()
            p += 6
            if (type != null) out += Highlight(index, annot, type, argb, raw.copyOfRange(p, p + count * 4).toRects(0))
            p += count * 4
        }
        return out
    }

    /** A note's (or any annotation's) text. */
    fun annotationText(index: Int, annotIndex: Int): String = PdfiumNative.annotContents(checkOpen(), index, annotIndex).orEmpty()

    fun removeAnnotation(index: Int, annotIndex: Int): Boolean = PdfiumNative.removeAnnot(checkOpen(), index, annotIndex)

    /** Adds a freehand drawing; each stroke is [x, y, x, y, ...] in display points. */
    fun addInk(index: Int, strokes: List<FloatArray>, argb: Int, width: Float): Boolean {
        val flat = ArrayList<Float>()
        for (s in strokes) {
            if (s.size < 2) continue
            flat += (s.size / 2).toFloat()
            s.forEach { flat += it }
        }
        return flat.isNotEmpty() && PdfiumNative.addInk(checkOpen(), index, flat.toFloatArray(), argb, width)
    }

    /** Outlines [rect] with a rectangle or, with [ellipse], an ellipse. */
    fun addShape(index: Int, rect: PageRect, ellipse: Boolean, argb: Int, width: Float): Boolean = PdfiumNative.addShape(
        checkOpen(), index, if (ellipse) AnnotType.CIRCLE.code else AnnotType.SQUARE.code,
        rect.left, rect.top, rect.right, rect.bottom, argb, width,
    )

    /** Adds a sticky note with [text], its icon at ([x], [y]). */
    fun addNote(index: Int, x: Float, y: Float, text: String, argb: Int): Boolean =
        PdfiumNative.addNote(checkOpen(), index, x, y, text, argb)

    // --- pages ---

    fun deletePages(indices: List<Int>): Boolean = PdfiumNative.deletePages(checkOpen(), indices.toIntArray())

    /** Turns pages clockwise by [quarterTurns] x 90 degrees. */
    fun rotatePages(indices: List<Int>, quarterTurns: Int): Boolean = PdfiumNative.rotatePages(checkOpen(), indices.toIntArray(), quarterTurns)

    /** Moves [indices] (in that order) so the first lands at [destIndex] of the result. */
    fun movePages(indices: List<Int>, destIndex: Int): Boolean = PdfiumNative.movePages(checkOpen(), indices.toIntArray(), destIndex)

    /** Inserts all pages of the PDF in [source] before page [insertAt]; returns how many. */
    fun importPages(source: ParcelFileDescriptor, insertAt: Int): Int {
        val result = PdfiumNative.importPages(checkOpen(), source.fd, source.statSize, insertAt)
        if (result < 0) throw PdfOpenException(-result)
        return result
    }

    /** Writes a new PDF of the pages at [indices] to [target]. */
    fun extractPages(indices: List<Int>, target: File) {
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        ParcelFileDescriptor.open(target, mode).use { pfd ->
            check(PdfiumNative.extractPages(checkOpen(), indices.toIntArray(), pfd.fd)) { "PDFium could not write the pages" }
        }
    }

    private fun FloatArray.toRects(from: Int): List<PageRect> =
        (from until size step 4).map { PageRect(this[it], this[it + 1], this[it + 2], this[it + 3]) }

    /** Places [bitmap] in [rect]: into the page content, or as a /Stamp annotation. */
    fun insertImage(index: Int, bitmap: Bitmap, rect: PageRect, asAnnotation: Boolean): Boolean =
        PdfiumNative.insertImage(checkOpen(), index, bitmap, rect.left, rect.top, rect.right, rect.bottom, asAnnotation)

    /** Writes Helvetica text into the page content, starting at ([x], [baseline]). */
    fun insertText(index: Int, text: String, x: Float, baseline: Float, fontSize: Float, argb: Int): Boolean =
        PdfiumNative.insertText(checkOpen(), index, text, x, baseline, fontSize, argb)

    /** Strokes polylines into the page content; a NaN pair in [points] starts a new line. */
    fun insertPath(index: Int, points: FloatArray, strokeWidth: Float, argb: Int): Boolean =
        PdfiumNative.insertPath(checkOpen(), index, points, strokeWidth, argb)

    /** Writes a copy to [target]. Incremental saves keep the original bytes as a prefix. */
    fun saveTo(target: File, incremental: Boolean) {
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        ParcelFileDescriptor.open(target, mode).use { pfd ->
            check(PdfiumNative.save(checkOpen(), pfd.fd, incremental)) { "PDFium could not save the document" }
            pfd.fileDescriptor.sync()
        }
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
        private const val MIN_ERROR = -1000L

        /** Opens [pfd]; the descriptor is duplicated, so the caller keeps ownership of [pfd]. */
        fun open(pfd: ParcelFileDescriptor, password: String? = null): PdfDocument {
            val length = pfd.statSize
            val fd = pfd.dup().detachFd()
            val result = PdfiumNative.open(fd, length, password)
            // Errors are small negative codes. A handle can look negative too: on devices with
            // tagged pointers (e.g. Samsung Galaxy, Android 14+) the top byte of a heap address is set.
            if (result in MIN_ERROR..-1L) throw PdfOpenException((-result).toInt())
            return PdfDocument(result)
        }
    }
}
