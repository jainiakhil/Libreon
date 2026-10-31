// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextTest {
    private fun word(text: String, index: Int): String = PdfText.wordAround(text, index).let { text.substring(it.first, it.last + 1) }

    @Test
    fun selectsTheWholeWord() {
        assertEquals("quick", word("The quick fox", 6))
        assertEquals("The", word("The quick fox", 0))
        assertEquals("fox", word("The quick fox", 12))
    }

    @Test
    fun keepsContractionsAndHyphenatedWords() {
        assertEquals("don't", word("I don't know", 3))
        assertEquals("well-known", word("a well-known fact", 8))
    }

    @Test
    fun punctuationAndSpacesAreSingleCharacters() {
        assertEquals(" ", word("a b", 1))
        assertEquals(",", word("a, b", 1))
        assertEquals("-", word("a - b", 2))
    }

    @Test
    fun clampsOutOfRangeAndHandlesEmpty() {
        assertEquals("end", word("the end", 99))
        assertTrue(PdfText.wordAround("", 0).isEmpty())
    }
}
