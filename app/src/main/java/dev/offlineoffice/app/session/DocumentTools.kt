// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import kotlinx.coroutines.flow.MutableSharedFlow

/** Find and word count, offered for every kind of document from the app bar. */
interface DocumentTools {
    /** Emits when the app bar asks the viewer to open its find bar. */
    val findRequests: MutableSharedFlow<Unit>

    /** Words and characters in the document and in the current selection. */
    suspend fun wordCount(): WordCount
}

fun findRequestFlow() = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

data class Count(val words: Int, val chars: Int)

/**
 * [document] and [selection] are null when they cannot be counted ([selection] also when
 * nothing is selected); [note] explains a missing figure.
 */
data class WordCount(val document: Count?, val selection: Count?, val note: String? = null)

/** Reads the totals out of Writer's status-bar word count line. */
object WriterWordCount {
    // "1,204 words, 6,880 characters" or "Selected: 3 of 1,204 words, 17 of 6,880 characters".
    private val pattern = Regex("""([\d.,  ' ]*\d)\s+words?\b.*?([\d.,  ' ]*\d)\s+characters?""")

    /** Document totals, or null when [line] has none (some versions show only the selection). */
    fun parse(line: String): Count? {
        if (line.trimStart().startsWith("Selected") && " of " !in line) return null
        val m = pattern.find(line) ?: return null
        return Count(lastNumber(m.groupValues[1]) ?: return null, lastNumber(m.groupValues[2]) ?: return null)
    }

    // "3 of 1,204" -> 1204: the total is the last number; group separators are dropped.
    private fun lastNumber(s: String): Int? =
        s.substringAfterLast(" of ").filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull()
}
