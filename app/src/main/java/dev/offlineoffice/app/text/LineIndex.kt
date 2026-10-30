// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.Charset

/** How a text file is opened, by size. */
enum class SizeClass {
    /** Loaded into the editor. */
    EDITABLE,
    /** Read-only, virtualised line list over the whole file. */
    READ_ONLY,
    /** Too big even for that: warn and offer head/tail windows. */
    HUGE;

    companion object {
        const val MB = 1024L * 1024L
        const val EDITABLE_MAX = 2 * MB
        const val READ_ONLY_MAX = 50 * MB

        fun of(sizeBytes: Long): SizeClass = when {
            sizeBytes <= EDITABLE_MAX -> EDITABLE
            sizeBytes <= READ_ONLY_MAX -> READ_ONLY
            else -> HUGE
        }
    }
}

/**
 * A streaming line index over the byte range [start, end) of [file], for files too large to edit.
 * Only every [BLOCK]th line start is stored, so memory stays small even with millions of lines;
 * lines are read back a block at a time. Line breaks are LF, CRLF or a lone CR, compared per code
 * unit so UTF-16/32 work too (ASCII-compatible multibyte charsets never use 0x0A/0x0D in trail bytes).
 */
class LineIndex private constructor(
    val file: File,
    val charset: Charset,
    val start: Long,
    val end: Long,
    private val unit: CodeUnit,
    private val blockStarts: LongArray,
    val lineCount: Int,
    /** Longest line seen, in code units (capped at [MAX_LINE_UNITS]); used to size a no-wrap view. */
    val longestLine: Int,
) {
    val blockCount: Int get() = (lineCount + BLOCK - 1) / BLOCK

    /** Reads the lines of block [block]. Lines longer than [MAX_LINE_UNITS] are cut and end with "…". */
    fun readBlock(block: Int): List<String> {
        require(block in 0 until blockCount)
        val from = blockStarts[block]
        val to = if (block + 1 < blockStarts.size) blockStarts[block + 1] else end
        val wanted = minOf(BLOCK, lineCount - block * BLOCK)
        val lines = ArrayList<String>(wanted)
        val w = unit.width
        val maxBytes = MAX_LINE_UNITS * w
        open(from).use { input ->
            val reader = UnitReader(input, to - from, unit)
            var buf = ByteArray(256)
            var len = 0
            var truncated = false
            fun emit() {
                val s = String(buf, 0, len, charset)
                lines += if (truncated) "$s…" else s
                len = 0
                truncated = false
            }
            while (lines.size < wanted) {
                val u = reader.next()
                if (u < 0) { emit(); break }
                if (u == LF_U || u == CR_U) {
                    if (u == CR_U && reader.peek() == LF_U) reader.next()
                    emit()
                    continue
                }
                if (len + w <= maxBytes) {
                    if (len + w > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + w))
                    reader.copyLast(buf, len)
                    len += w
                } else {
                    truncated = true
                }
            }
        }
        while (lines.size < wanted) lines += ""
        return lines
    }

    private fun open(at: Long): InputStream {
        val fis = FileInputStream(file)
        fis.channel.position(at)
        return BufferedInputStream(fis, 64 * 1024)
    }

    /** How code units are laid out in the charset. */
    enum class CodeUnit(val width: Int, val bigEndian: Boolean) {
        BYTE(1, false), UTF16LE(2, false), UTF16BE(2, true), UTF32LE(4, false), UTF32BE(4, true);

        companion object {
            fun of(charset: Charset): CodeUnit = when (charset.name().uppercase()) {
                "UTF-16LE" -> UTF16LE
                "UTF-16BE", "UTF-16" -> UTF16BE
                "UTF-32LE" -> UTF32LE
                "UTF-32BE", "UTF-32" -> UTF32BE
                else -> BYTE
            }
        }
    }

    /** Reads code units from a byte stream limited to [limit] bytes. */
    private class UnitReader(private val input: InputStream, private var limit: Long, private val unit: CodeUnit) {
        private val last = ByteArray(4)
        private val peeked = ByteArray(4)
        private var hasPeek = false
        private var peekValue = -1

        fun next(): Int {
            if (hasPeek) {
                hasPeek = false
                peeked.copyInto(last)
                return peekValue
            }
            return read(last)
        }

        fun peek(): Int {
            if (!hasPeek) {
                peekValue = read(peeked)
                hasPeek = true
            }
            return peekValue
        }

        fun copyLast(dest: ByteArray, at: Int) = last.copyInto(dest, at, 0, unit.width)

        private fun read(into: ByteArray): Int {
            val w = unit.width
            if (limit < w) return -1
            for (i in 0 until w) {
                val b = input.read()
                if (b < 0) return -1
                into[i] = b.toByte()
            }
            limit -= w
            return unitValue(into, 0, unit)
        }
    }

    companion object {
        const val BLOCK = 64
        const val MAX_LINE_UNITS = 10_000
        private const val LF_U = 0x0A
        private const val CR_U = 0x0D

        private fun unitValue(b: ByteArray, i: Int, unit: CodeUnit): Int {
            fun u(k: Int) = b[i + k].toInt() and 0xFF
            return when (unit) {
                CodeUnit.BYTE -> u(0)
                CodeUnit.UTF16LE -> u(0) or (u(1) shl 8)
                CodeUnit.UTF16BE -> (u(0) shl 8) or u(1)
                CodeUnit.UTF32LE -> u(0) or (u(1) shl 8) or (u(2) shl 16) or (u(3) shl 24)
                CodeUnit.UTF32BE -> (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3)
            }
        }

        /**
         * Builds the index by streaming [file] between [start] and [end]. With [skipPartialFirstLine]
         * the index begins after the first line break (used for a tail window that starts mid-line).
         * [onProgress] gets the fraction done; [isCancelled] aborts the scan early.
         */
        fun build(
            file: File,
            charset: Charset,
            start: Long,
            end: Long,
            skipPartialFirstLine: Boolean = false,
            onProgress: (Float) -> Unit = {},
            isCancelled: () -> Boolean = { false },
        ): LineIndex {
            val unit = CodeUnit.of(charset)
            val w = unit.width
            var blocks = LongArray(1024)
            var blockCount = 0
            var lines = 0
            var longest = 0
            var lineLen = 0
            var firstStart = -1L
            fun lineStart(at: Long) {
                if (firstStart < 0) firstStart = at
                if (lines % BLOCK == 0) {
                    if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
                    blocks[blockCount++] = at
                }
                lines++
                if (lineLen > longest) longest = minOf(lineLen, MAX_LINE_UNITS)
                lineLen = 0
            }
            var waitingForFirstBreak = skipPartialFirstLine
            if (!waitingForFirstBreak) lineStart(start)

            val buf = ByteArray(1024 * 1024)
            var pos = start
            var prevCr = false
            FileInputStream(file).use { fis ->
                fis.channel.position(start)
                var carry = 0
                while (pos < end) {
                    if (isCancelled()) throw InterruptedException("cancelled")
                    val want = minOf((buf.size - carry).toLong(), end - pos - carry).toInt()
                    val n = if (want > 0) fis.read(buf, carry, want) else 0
                    if (n <= 0 && carry == 0) break
                    val avail = carry + maxOf(n, 0)
                    val usable = avail - avail % w
                    var i = 0
                    while (i < usable) {
                        val u = unitValue(buf, i, unit)
                        val at = pos + i
                        if (prevCr) {
                            prevCr = false
                            if (u == LF_U) {
                                if (waitingForFirstBreak) waitingForFirstBreak = false
                                lineStart(at + w)
                                i += w
                                continue
                            }
                            if (waitingForFirstBreak) waitingForFirstBreak = false
                            lineStart(at)
                        }
                        when (u) {
                            CR_U -> prevCr = true
                            LF_U -> {
                                if (waitingForFirstBreak) waitingForFirstBreak = false
                                lineStart(at + w)
                            }
                            else -> lineLen++
                        }
                        i += w
                    }
                    pos += usable
                    carry = avail - usable
                    if (carry > 0) buf.copyInto(buf, 0, usable, avail)
                    if (n <= 0) break
                    onProgress(((pos - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            if (prevCr) {
                if (waitingForFirstBreak) waitingForFirstBreak = false
                lineStart(end)
            }
            if (lines == 0) lineStart(start) // a tail window without any line break: show it all
            if (lineLen > longest) longest = minOf(lineLen, MAX_LINE_UNITS)
            return LineIndex(file, charset, firstStart, end, unit, blocks.copyOf(blockCount), lines, longest)
        }
    }
}
