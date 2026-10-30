// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import android.graphics.Bitmap
import dev.offlineoffice.pdf.PageRect

/**
 * Something the user placed on a page but has not committed yet. Marks can be moved,
 * resized and deleted; committing writes them into the PDF. Rects are display points.
 */
sealed interface PdfMark {
    val id: Long
    val page: Int
    val rect: PageRect

    fun moved(rect: PageRect): PdfMark

    /** Free-placed single-line text, flattened as Helvetica. */
    data class Text(override val id: Long, override val page: Int, override val rect: PageRect, val text: String, val argb: Int) : PdfMark {
        override fun moved(rect: PageRect) = copy(rect = rect)
    }

    /** A check or cross mark for flat (non-interactive) forms, flattened as a stroked path. */
    data class Symbol(override val id: Long, override val page: Int, override val rect: PageRect, val cross: Boolean, val argb: Int) : PdfMark {
        override fun moved(rect: PageRect) = copy(rect = rect)
    }

    /**
     * A signature or initials image (transparent PNG). Flattened into the page content by
     * default; [asAnnotation] keeps it as a /Stamp annotation instead.
     */
    data class Signature(
        override val id: Long,
        override val page: Int,
        override val rect: PageRect,
        val image: Bitmap,
        val asAnnotation: Boolean = false,
    ) : PdfMark {
        override fun moved(rect: PageRect) = copy(rect = rect)
    }
}

/** Pure geometry shared by the on-screen preview and the committed PDF content. */
object MarkGeometry {
    /** Line height as a multiple of the font size for text marks. */
    const val TEXT_LINE_FACTOR = 1.2f

    /** Baseline position below the top of a text mark, as a multiple of the font size. */
    const val TEXT_BASELINE_FACTOR = 0.93f

    fun fontSizeFor(rect: PageRect): Float = rect.height / TEXT_LINE_FACTOR

    fun baselineFor(rect: PageRect): Float = rect.top + fontSizeFor(rect) * TEXT_BASELINE_FACTOR

    /** Stroke width for check and cross marks. */
    fun symbolStroke(rect: PageRect): Float = minOf(rect.width, rect.height) * 0.12f

    /** Polyline for a mark in [rect]: x,y pairs, NaN pairs separating strokes. */
    fun symbolPoints(rect: PageRect, cross: Boolean): FloatArray {
        val unit = if (cross) {
            floatArrayOf(0.2f, 0.2f, 0.8f, 0.8f, Float.NaN, Float.NaN, 0.8f, 0.2f, 0.2f, 0.8f)
        } else {
            floatArrayOf(0.15f, 0.55f, 0.4f, 0.8f, 0.85f, 0.2f)
        }
        return FloatArray(unit.size) { i ->
            val v = unit[i]
            when {
                v.isNaN() -> Float.NaN
                i % 2 == 0 -> rect.left + v * rect.width
                else -> rect.top + v * rect.height
            }
        }
    }

    /** A rect of [width] x [height] centred on (cx, cy), shifted to stay inside the page. */
    fun centredRect(cx: Float, cy: Float, width: Float, height: Float, pageWidth: Float, pageHeight: Float): PageRect {
        val w = width.coerceAtMost(pageWidth)
        val h = height.coerceAtMost(pageHeight)
        val left = (cx - w / 2).coerceIn(0f, pageWidth - w)
        val top = (cy - h / 2).coerceIn(0f, pageHeight - h)
        return PageRect(left, top, left + w, top + h)
    }

    /** Moves [rect] by (dx, dy) while keeping it inside the page. */
    fun offset(rect: PageRect, dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): PageRect {
        val left = (rect.left + dx).coerceIn(0f, (pageWidth - rect.width).coerceAtLeast(0f))
        val top = (rect.top + dy).coerceIn(0f, (pageHeight - rect.height).coerceAtLeast(0f))
        return PageRect(left, top, left + rect.width, top + rect.height)
    }

    /**
     * Resizes [rect] from its bottom-right corner by (dx, dy). With [aspect] (width / height)
     * the ratio is kept, driven by the larger change. Never smaller than [minSize] points.
     */
    fun resize(rect: PageRect, dx: Float, dy: Float, aspect: Float?, minSize: Float = 6f): PageRect {
        var w = (rect.width + dx).coerceAtLeast(minSize)
        var h = (rect.height + dy).coerceAtLeast(minSize)
        if (aspect != null) {
            if (kotlin.math.abs(dx) >= kotlin.math.abs(dy) * aspect) h = w / aspect else w = h * aspect
            if (h < minSize) { h = minSize; w = h * aspect }
            if (w < minSize) { w = minSize; h = w / aspect }
        }
        return PageRect(rect.left, rect.top, rect.left + w, rect.top + h)
    }
}
