// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeFileTest {
    private fun temp(bytes: ByteArray): File = File.createTempFile("lineindex", ".txt").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    private fun allLines(index: LineIndex): List<String> = (0 until index.blockCount).flatMap { index.readBlock(it) }

    @Test fun sizeClassification() {
        val mb = SizeClass.MB
        assertEquals(SizeClass.EDITABLE, SizeClass.of(0))
        assertEquals(SizeClass.EDITABLE, SizeClass.of(2 * mb))
        assertEquals(SizeClass.READ_ONLY, SizeClass.of(2 * mb + 1))
        assertEquals(SizeClass.READ_ONLY, SizeClass.of(50 * mb))
        assertEquals(SizeClass.HUGE, SizeClass.of(50 * mb + 1))
    }

    @Test fun indexesLfCrlfAndCr() {
        val file = temp("one\ntwo\r\nthree\rfour".toByteArray())
        val index = LineIndex.build(file, Charsets.UTF_8, 0, file.length())
        assertEquals(4, index.lineCount)
        assertEquals(listOf("one", "two", "three", "four"), allLines(index))
    }

    @Test fun trailingBreakGivesEmptyLastLine() {
        val file = temp("a\r\nb\r\n".toByteArray())
        val index = LineIndex.build(file, Charsets.UTF_8, 0, file.length())
        assertEquals(listOf("a", "b", ""), allLines(index))
    }

    @Test fun utf16WithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "α\r\nβ\nγ".toByteArray(Charsets.UTF_16LE)
        val file = temp(bytes)
        val index = LineIndex.build(file, Charsets.UTF_16LE, 2, file.length())
        assertEquals(listOf("α", "β", "γ"), allLines(index))
    }

    @Test fun manyLinesAcrossBlocksAndBuffers() {
        // ~5 MB, many blocks and several 1 MB read buffers.
        val sb = StringBuilder()
        var n = 0
        while (sb.length < 5 * 1024 * 1024) sb.append("Line ").append(++n).append(": the quick brown fox\n")
        val file = temp(sb.toString().toByteArray())
        assertEquals(SizeClass.READ_ONLY, SizeClass.of(file.length()))
        val index = LineIndex.build(file, Charsets.UTF_8, 0, file.length())
        assertEquals(n + 1, index.lineCount) // plus the empty line after the final break
        assertEquals("Line 1: the quick brown fox", index.readBlock(0)[0])
        val last = n - 1 // 0-based index of "Line n"
        assertEquals("Line $n: the quick brown fox", index.readBlock(last / LineIndex.BLOCK)[last % LineIndex.BLOCK])
        assertEquals("Line 12345: the quick brown fox", index.readBlock(12344 / LineIndex.BLOCK)[12344 % LineIndex.BLOCK])
    }

    @Test fun tailWindowSkipsPartialFirstLine() {
        val file = temp("first line\nsecond line\nthird".toByteArray())
        val index = LineIndex.build(file, Charsets.UTF_8, 3, file.length(), skipPartialFirstLine = true)
        assertEquals(listOf("second line", "third"), allLines(index))
    }

    @Test fun longLinesAreCut() {
        val file = temp(("x".repeat(LineIndex.MAX_LINE_UNITS + 50) + "\nshort").toByteArray())
        val index = LineIndex.build(file, Charsets.UTF_8, 0, file.length())
        val lines = allLines(index)
        assertTrue(lines[0].endsWith("…"))
        assertEquals(LineIndex.MAX_LINE_UNITS + 1, lines[0].length)
        assertEquals("short", lines[1])
        assertEquals(LineIndex.MAX_LINE_UNITS, index.longestLine)
    }
}
