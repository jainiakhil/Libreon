// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvParserTest {
    @Test fun quotedFieldsEscapedQuotesAndEmbeddedNewlines() {
        val text = listOf(File("../testdata/quoted.csv"), File("testdata/quoted.csv")).first { it.exists() }.readText()
        val rows = CsvParser.parse(text)
        assertEquals(5, rows.size)
        assertEquals(listOf("id", "name", "notes", "amount"), rows[0])
        assertEquals(listOf("1", "Smith, John", "He said \"hello\"", "12.50"), rows[1])
        assertEquals("Line one\nline two\nline three", rows[2][2])
        assertEquals(listOf("3", "", "empty name", "0"), rows[3])
        assertEquals("tab\there", rows[4][2])
    }

    @Test fun crlfAndCrRecordSeparators() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), CsvParser.parse("a,b\r\nc,d\r\n", ','))
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), CsvParser.parse("a,b\rc,d", ','))
        assertEquals(listOf(listOf("x\r\ny")), CsvParser.parse("\"x\r\ny\"\r\n", ','))
    }

    @Test fun emptyFieldsAndLines() {
        assertEquals(listOf(listOf("a", "", "")), CsvParser.parse("a,,", ','))
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), CsvParser.parse("a\n\nb\n", ','))
        assertEquals(listOf(listOf("", "")), CsvParser.parse("\"\",\"\"", ','))
        assertEquals(emptyList<List<String>>(), CsvParser.parse("", ','))
    }

    @Test fun lenientQuotes() {
        // A quote inside an unquoted field is literal; text after a closing quote is kept.
        assertEquals(listOf(listOf("5\" disk", "ab")), CsvParser.parse("5\" disk,\"a\"b", ','))
        // An unterminated quote runs to the end of the input.
        assertEquals(listOf(listOf("open", "rest,of\ntext")), CsvParser.parse("open,\"rest,of\ntext", ','))
    }

    @Test fun delimiterDetection() {
        assertEquals(',', CsvParser.detectDelimiter("a,b,c\n1,2,3\n"))
        assertEquals(';', CsvParser.detectDelimiter("Name;City;Score\nAnna;Wien;3,5\n\"Bob; Jr.\";Graz;4\n"))
        assertEquals('\t', CsvParser.detectDelimiter("a\tb\tc\n1,5\t2\t3\n"))
        // Commas inside quotes must not count.
        assertEquals(';', CsvParser.detectDelimiter("\"a,b,c\";d\n\"e,f\";g\n"))
        assertEquals(',', CsvParser.detectDelimiter("single column\nno delimiters\n"))
    }
}
