// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

/** Word boundaries in a page's text, for selecting by long press. */
object PdfText {
    /**
     * The word containing character [index] of [text], as an inclusive index range. Letters,
     * digits and apostrophes or hyphens between them form words; any other character is a
     * word of its own.
     */
    fun wordAround(text: String, index: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = index.coerceIn(0, text.lastIndex)
        if (!text[at].isLetterOrDigit()) return at..at
        var start = at
        while (start > 0 && joins(text, start - 1)) start--
        var end = at
        while (end < text.lastIndex && joins(text, end + 1)) end++
        return start..end
    }

    private fun joins(text: String, i: Int): Boolean {
        val c = text[i]
        if (c.isLetterOrDigit()) return true
        if (c != '\'' && c != '’' && c != '-') return false
        return i > 0 && i < text.lastIndex && text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
    }
}
