// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import dev.offlineoffice.pdf.PageRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfLayoutMathTest {
    @Test fun fitScaleFillsViewportMinusPadding() {
        assertEquals(2f, PdfLayoutMath.fitScale(1240, 612f, 8f), 1e-4f)
    }

    @Test fun pageViewRoundTrip() {
        val r = PageRect(10f, 20f, 110f, 70f)
        val v = PdfLayoutMath.pageToView(r, 2.5f)
        assertEquals(PageRect(25f, 50f, 275f, 175f), v)
        assertEquals(10f to 20f, PdfLayoutMath.viewToPage(v.left, v.top, 2.5f))
    }

    @Test fun zoomKeepsFocalPointInSamePage() {
        // Pages 1000px tall, 10px gaps; focal point 300px into page 2 (index 1).
        val anchor = PdfLayoutMath.anchorAfterZoom(
            itemCount = 5, oldHeight = { 1000f }, newHeight = { 2000f }, spacing = 10f,
            first = ListAnchor(1, 100), focalY = 200f,
        )
        // 300px of 1000 = 30% -> 600px of 2000; the item top must sit at focalY - 600.
        assertEquals(ListAnchor(1, 400), anchor)
    }

    @Test fun zoomCarriesOverflowIntoNextPage() {
        // Focal point is in page index 2 although the list starts at index 1.
        val anchor = PdfLayoutMath.anchorAfterZoom(
            itemCount = 5, oldHeight = { 500f }, newHeight = { 1000f }, spacing = 10f,
            first = ListAnchor(1, 400), focalY = 300f,
        )
        // rem = 700 -> past page 1 (510) -> 190 into page 2 = 38% -> 380 of 1000.
        assertEquals(ListAnchor(2, 80), anchor)
    }

    @Test fun zoomOutWalksBackToEarlierPages() {
        val anchor = PdfLayoutMath.anchorAfterZoom(
            itemCount = 5, oldHeight = { 2000f }, newHeight = { 500f }, spacing = 10f,
            first = ListAnchor(3, 100), focalY = 400f,
        )
        // 500/2000 = 25% -> 125 of 500; offset 125 - 400 = -275 -> previous page: 510 - 275 = 235.
        assertEquals(ListAnchor(2, 235), anchor)
    }

    @Test fun zoomAtTopClampsToZero() {
        val anchor = PdfLayoutMath.anchorAfterZoom(3, { 1000f }, { 500f }, 10f, ListAnchor(0, 0), 600f)
        assertEquals(ListAnchor(0, 0), anchor)
    }

    @Test fun horizontalScrollKeepsFocalX() {
        // content x under focal = 100 + 500 - 8 = 592 -> doubled 1184 + 8 - 500.
        assertEquals(692, PdfLayoutMath.scrollXAfterZoom(100, 500f, 2f, 8f))
        assertEquals(0, PdfLayoutMath.scrollXAfterZoom(0, 10f, 0.5f, 8f))
    }

    @Test fun detailRegionAddsMarginAndClips() {
        assertArrayEquals(intArrayOf(90, 0, 1000, 550), PdfLayoutMath.detailRegion(200f, 0f, 1300f, 500f, 1000, 2000))
        assertNull(PdfLayoutMath.detailRegion(0f, 0f, 0f, 100f, 1000, 2000))
    }

    @Test fun fitInsideCentresWithAspect() {
        assertEquals(PageRect(25f, 0f, 75f, 50f), PdfLayoutMath.fitInside(PageRect(0f, 0f, 100f, 50f), 1f))
        assertEquals(PageRect(0f, 15f, 100f, 35f), PdfLayoutMath.fitInside(PageRect(0f, 0f, 100f, 50f), 5f))
    }
}
