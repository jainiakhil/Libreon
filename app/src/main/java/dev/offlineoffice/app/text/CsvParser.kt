// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

/**
 * RFC 4180 CSV reader: quoted fields, doubled quotes, delimiters and line breaks (LF, CRLF, CR)
 * inside quotes. Lenient where the RFC is strict: text after a closing quote is kept, and a quote
 * inside an unquoted field is literal. A trailing line break does not add an empty record.
 */
object CsvParser {
    val CANDIDATES = charArrayOf(',', ';', '\t')

    fun parse(text: CharSequence, delimiter: Char = detectDelimiter(text), maxRows: Int = Int.MAX_VALUE): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        val n = text.length
        var inQuotes = false
        var fieldStarted = false // something (even "") was read for the current field

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
            fieldStarted = false
        }
        fun endRow() {
            endField()
            rows.add(row)
            row = ArrayList()
        }

        while (i < n && rows.size < maxRows) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                    inQuotes = false
                } else {
                    field.append(c)
                }
                i++
                continue
            }
            when (c) {
                '"' -> if (!fieldStarted && field.isEmpty()) { inQuotes = true; fieldStarted = true } else field.append(c)
                delimiter -> endField()
                '\r' -> { endRow(); if (i + 1 < n && text[i + 1] == '\n') i++ }
                '\n' -> endRow()
                else -> { field.append(c); fieldStarted = true }
            }
            i++
        }
        // Flush the last record unless the text ended with a line break.
        if (rows.size < maxRows && (fieldStarted || field.isNotEmpty() || row.isNotEmpty() || inQuotes)) endRow()
        return rows
    }

    /**
     * Picks comma, semicolon or tab by parsing a sample with each and preferring the one that splits
     * the most records into the same number (> 1) of fields. Ties go to the earlier candidate.
     */
    fun detectDelimiter(text: CharSequence, sampleRows: Int = 20): Char {
        val sample = text.subSequence(0, minOf(text.length, 64 * 1024))
        var best = ','
        var bestScore = 0
        for (d in CANDIDATES) {
            val counts = parse(sample, d, sampleRows).map { it.size }
            if (counts.isEmpty()) continue
            val mode = counts.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key })) ?: continue
            if (mode.key <= 1) continue
            val score = mode.value * 1000 + mode.key
            if (score > bestScore) { best = d; bestScore = score }
        }
        return best
    }
}
