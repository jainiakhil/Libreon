// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Pretty-prints and validates JSON with the platform's org.json. */
object JsonFormatter {
    sealed interface Result {
        data class Formatted(val text: String) : Result
        /** [line] and [column] are 1-based; [offset] is the 0-based character index. */
        data class Invalid(val message: String, val line: Int, val column: Int, val offset: Int) : Result
    }

    fun format(text: String, indent: Int = 2): Result {
        val tokener = JSONTokener(text)
        return try {
            val value = tokener.nextValue()
            // Anything but whitespace after the value is an error that nextValue() does not report.
            if (tokener.more() && tokener.nextClean() != 0.toChar()) {
                throw JSONException("Unexpected text after the JSON value at character ${currentOffset(tokener, text)}")
            }
            val pretty = when (value) {
                is JSONObject -> value.toString(indent)
                is JSONArray -> value.toString(indent)
                else -> text.trim() // a bare string, number, boolean or null is already as pretty as it gets
            }
            // Android's org.json escapes "/" as "\/"; every "/" it writes is escaped, so this is safe.
            Result.Formatted(pretty.replace("\\/", "/") + "\n")
        } catch (e: JSONException) {
            val raw = e.message.orEmpty()
            val offset = Regex("at character (\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                ?.coerceIn(0, text.length) ?: currentOffset(tokener, text)
            // org.json appends the whole input after " of "; keep only the description.
            val description = raw.substringBefore(" at character").substringBefore(" of ").ifBlank { "Invalid JSON" }
            val (line, column) = lineAndColumn(text, offset)
            Result.Invalid(description, line, column, offset)
        }
    }

    fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        for (i in 0 until offset.coerceAtMost(text.length)) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
        }
        return line to (offset - lineStart + 1)
    }

    /** JSONTokener.toString() is " at character N of …" on Android. */
    private fun currentOffset(tokener: JSONTokener, text: String): Int =
        Regex("at character (\\d+)").find(tokener.toString())?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, text.length) ?: 0
}
