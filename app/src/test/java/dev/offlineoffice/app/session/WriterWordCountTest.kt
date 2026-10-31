// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WriterWordCountTest {
    @Test
    fun readsTotalsWithoutSelection() {
        assertEquals(Count(1204, 6880), WriterWordCount.parse("1,204 words, 6,880 characters"))
        assertEquals(Count(1, 5), WriterWordCount.parse("1 word, 5 characters"))
    }

    @Test
    fun readsTotalsFromSelectionLine() {
        assertEquals(Count(1204, 6880), WriterWordCount.parse("Selected: 3 of 1,204 words, 17 of 6,880 characters"))
    }

    @Test
    fun handlesOtherGroupSeparators() {
        assertEquals(Count(12345, 67890), WriterWordCount.parse("12 345 words, 67.890 characters"))
    }

    @Test
    fun rejectsUnrelatedText() {
        assertNull(WriterWordCount.parse("Page 1 of 3"))
        assertNull(WriterWordCount.parse(""))
        // Seen in this engine: with a selection, only the selection is reported.
        assertNull(WriterWordCount.parse("Selected: 1 word, 5 characters"))
    }
}
