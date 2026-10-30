// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import dev.offlineoffice.pdf.PageRect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkGeometryTest {
    @Test fun textMetricsFollowRectHeight() {
        val r = PageRect(0f, 100f, 50f, 112f)
        assertEquals(10f, MarkGeometry.fontSizeFor(r), 1e-4f)
        assertEquals(109.3f, MarkGeometry.baselineFor(r), 1e-4f)
    }

    @Test fun checkPointsScaleIntoRect() {
        val p = MarkGeometry.symbolPoints(PageRect(10f, 20f, 30f, 40f), cross = false)
        assertEquals(6, p.size)
        assertEquals(10f + 0.15f * 20, p[0], 1e-4f)
        assertEquals(20f + 0.8f * 20, p[3], 1e-4f)
    }

    @Test fun crossHasTwoStrokesSeparatedByNaN() {
        val p = MarkGeometry.symbolPoints(PageRect(0f, 0f, 10f, 10f), cross = true)
        assertEquals(10, p.size)
        assertTrue(p[4].isNaN() && p[5].isNaN())
    }

    @Test fun centredRectStaysOnPage() {
        assertEquals(PageRect(0f, 90f, 20f, 110f), MarkGeometry.centredRect(2f, 100f, 20f, 20f, 600f, 800f))
        assertEquals(PageRect(580f, 780f, 600f, 800f), MarkGeometry.centredRect(599f, 799f, 20f, 20f, 600f, 800f))
    }

    @Test fun offsetClampsToPage() {
        val moved = MarkGeometry.offset(PageRect(10f, 10f, 30f, 20f), -50f, 5f, 100f, 100f)
        assertEquals(PageRect(0f, 15f, 20f, 25f), moved)
    }

    @Test fun resizeKeepsAspectWhenAsked() {
        val r = MarkGeometry.resize(PageRect(0f, 0f, 40f, 20f), 40f, 0f, aspect = 2f)
        assertEquals(PageRect(0f, 0f, 80f, 40f), r)
        val tiny = MarkGeometry.resize(PageRect(0f, 0f, 40f, 20f), -100f, -100f, aspect = 2f)
        assertEquals(6f, tiny.height, 1e-4f)
        assertEquals(12f, tiny.width, 1e-4f)
    }

    @Test fun xrefDetection() {
        assertEquals(1234L, PdfXref.lastStartXref("trailer\n<<>>\nstartxref\n1234\n%%EOF\n".toByteArray()))
        assertEquals(9L, PdfXref.lastStartXref("startxref 5 %%EOF startxref\r\n9\r\n%%EOF".toByteArray()))
        assertTrue(PdfXref.isClassicXrefAt("xref\n0 1".toByteArray()))
        assertFalse(PdfXref.isClassicXrefAt("12 0 obj <</Type/XRef".toByteArray()))
        val f = File.createTempFile("xref", ".pdf")
        try {
            val body = "%PDF-1.4\n"
            f.writeText(body + "xref\n0 1\ntrailer<<>>\nstartxref\n${body.length}\n%%EOF\n")
            assertTrue(PdfXref.endsWithClassicXref(f))
            f.writeText(body + "5 0 obj<</Type/XRef>>\nstartxref\n${body.length}\n%%EOF\n")
            assertFalse(PdfXref.endsWithClassicXref(f))
        } finally {
            f.delete()
        }
    }
}
