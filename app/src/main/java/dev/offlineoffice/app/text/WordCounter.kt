// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

/**
 * Counts words the way word processors do: runs of letters and digits, kept together by an
 * apostrophe or hyphen between letters ("don't", "well-known") and by a point or comma
 * between digits ("3.14", "1,000"). Chinese and Japanese characters count one word each.
 * Linear and allocation-free, for multi-megabyte texts.
 */
object WordCounter {
    fun count(text: CharSequence): Int {
        var words = 0
        var inWord = false
        var i = 0
        val n = text.length
        while (i < n) {
            val cp = Character.codePointAt(text, i)
            val size = Character.charCount(cp)
            when {
                isIdeograph(cp) -> {
                    words++
                    inWord = false
                }
                Character.isLetterOrDigit(cp) || isMark(cp) -> {
                    if (!inWord) words++
                    inWord = true
                }
                inWord && joins(text, i, cp, size) -> Unit
                else -> inWord = false
            }
            i += size
        }
        return words
    }

    /** Characters (code points), not counting line breaks. */
    fun countChars(text: CharSequence): Int {
        var chars = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '\n' && c != '\r') chars++
            i += if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text[i + 1])) 2 else 1
        }
        return chars
    }

    // A joiner keeps the word going only when the next character continues it.
    private fun joins(text: CharSequence, i: Int, cp: Int, size: Int): Boolean {
        val next = i + size
        if (next >= text.length) return false
        val after = Character.codePointAt(text, next)
        val before = Character.codePointBefore(text, i)
        return when (cp) {
            '\''.code, 0x2019, '-'.code, 0x2010, 0x2011 ->
                Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after) && !isIdeograph(after)
            '.'.code, ','.code -> Character.isDigit(before) && Character.isDigit(after)
            else -> false
        }
    }

    // Combining marks (accents written separately, Indic vowel signs) belong to their word.
    private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isIdeograph(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}
