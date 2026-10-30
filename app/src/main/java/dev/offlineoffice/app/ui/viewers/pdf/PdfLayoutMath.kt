// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import dev.offlineoffice.pdf.PageRect

/** A scroll position in a vertical page list: item index plus pixels scrolled into it. */
data class ListAnchor(val index: Int, val offset: Int)

/** Pure page <-> view math for the PDF viewer, kept free of Android types for unit tests. */
object PdfLayoutMath {
    /** Pixels per PDF point that make the widest page fill the viewport at zoom 1. */
    fun fitScale(viewportWidthPx: Int, maxPageWidthPts: Float, paddingPx: Float): Float =
        ((viewportWidthPx - 2 * paddingPx) / maxPageWidthPts).coerceAtLeast(0.01f)

    /** Converts a point in page-box pixels to display points. */
    fun viewToPage(x: Float, y: Float, scale: Float): Pair<Float, Float> = x / scale to y / scale

    /** Converts display points to page-box pixels. */
    fun pageToView(rect: PageRect, scale: Float): PageRect =
        PageRect(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)

    /**
     * Where the list must scroll so that the content under [focalY] (viewport pixels) stays
     * under it when every item's height changes from [oldHeight] to [newHeight]. [spacing] is
     * the fixed gap between items, which does not scale.
     */
    fun anchorAfterZoom(
        itemCount: Int,
        oldHeight: (Int) -> Float,
        newHeight: (Int) -> Float,
        spacing: Float,
        first: ListAnchor,
        focalY: Float,
    ): ListAnchor {
        if (itemCount == 0) return ListAnchor(0, 0)
        var index = first.index.coerceIn(0, itemCount - 1)
        var rem = first.offset + focalY
        while (index < itemCount - 1 && rem > oldHeight(index) + spacing) {
            rem -= oldHeight(index) + spacing
            index++
        }
        val fraction = (rem / oldHeight(index)).coerceIn(0f, 1f)
        var offset = fraction * newHeight(index) - focalY
        while (offset < 0 && index > 0) {
            index--
            offset += newHeight(index) + spacing
        }
        return ListAnchor(index, offset.coerceAtLeast(0f).toInt())
    }

    /** New horizontal scroll keeping [focalX] fixed when content scales by [factor] around [paddingPx]. */
    fun scrollXAfterZoom(scrollX: Int, focalX: Float, factor: Float, paddingPx: Float): Int =
        ((scrollX + focalX - paddingPx) * factor + paddingPx - focalX).coerceAtLeast(0f).toInt()

    /**
     * The visible part of a page box, grown by [margin] (fraction of its size) on each side and
     * clipped to the page: [left, top, right, bottom] in page-box pixels, or null if hidden.
     */
    fun detailRegion(
        visibleLeft: Float, visibleTop: Float, visibleRight: Float, visibleBottom: Float,
        pageWidthPx: Int, pageHeightPx: Int, margin: Float = 0.1f,
    ): IntArray? {
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
        val mx = (visibleRight - visibleLeft) * margin
        val my = (visibleBottom - visibleTop) * margin
        val l = (visibleLeft - mx).toInt().coerceIn(0, pageWidthPx)
        val t = (visibleTop - my).toInt().coerceIn(0, pageHeightPx)
        val r = (visibleRight + mx).toInt().coerceIn(0, pageWidthPx)
        val b = (visibleBottom + my).toInt().coerceIn(0, pageHeightPx)
        return if (r > l && b > t) intArrayOf(l, t, r, b) else null
    }

    /** Largest rect with [aspect] (width / height) centred inside [box]. */
    fun fitInside(box: PageRect, aspect: Float): PageRect {
        val w: Float
        val h: Float
        if (box.width / box.height > aspect) {
            h = box.height; w = h * aspect
        } else {
            w = box.width; h = w / aspect
        }
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        return PageRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}
