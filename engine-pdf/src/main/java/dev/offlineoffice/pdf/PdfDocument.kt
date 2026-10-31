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

    val pageCount: Int = PdfiumNative.pageCount(handle)

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

    /** Adds a /Highlight annotation over [rects] in [argb], recording [text] as its contents. */
    fun addHighlight(index: Int, rects: List<PageRect>, argb: Int, text: String): Boolean {
        val flat = FloatArray(rects.size * 4)
        rects.forEachIndexed { i, r ->
            flat[i * 4] = r.left; flat[i * 4 + 1] = r.top; flat[i * 4 + 2] = r.right; flat[i * 4 + 3] = r.bottom
        }
        return PdfiumNative.addHighlight(checkOpen(), index, flat, argb, text)
    }

    /** The highlight annotations on page [index]. */
    fun highlights(index: Int): List<Highlight> {
        val raw = PdfiumNative.highlights(checkOpen(), index)
        val out = ArrayList<Highlight>(raw[0].toInt())
        var p = 1
        repeat(raw[0].toInt()) {
            val annot = raw[p].toInt()
            val argb = (0xFF shl 24) or (raw[p + 1].toInt() shl 16) or (raw[p + 2].toInt() shl 8) or raw[p + 3].toInt()
            val count = raw[p + 4].toInt()
            p += 5
            out += Highlight(index, annot, argb, raw.copyOfRange(p, p + count * 4).toRects(0))
            p += count * 4
        }
        return out
    }

    fun removeAnnotation(index: Int, annotIndex: Int): Boolean = PdfiumNative.removeAnnot(checkOpen(), index, annotIndex)

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
