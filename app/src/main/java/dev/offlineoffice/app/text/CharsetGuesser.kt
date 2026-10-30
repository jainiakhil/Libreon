// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.nio.charset.Charset

/**
 * Charset guesses for bytes that have no BOM and are not valid UTF-8, best first.
 *
 * Android does not expose ICU's CharsetDetector in the public SDK, so this is a small heuristic:
 * UTF-16 by its pattern of zero bytes, East Asian multibyte charsets when high bytes come in pairs
 * and decode to mostly CJK characters, otherwise windows-1252 and finally ISO-8859-1 (which
 * accepts any byte sequence, so the text always round-trips unchanged).
 */
object CharsetGuesser {
    private const val SAMPLE_BYTES = 64 * 1024

    fun candidates(bytes: ByteArray): List<String> {
        val sample = if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes
        val out = ArrayList<String>()
        utf16(sample)?.let(out::add)
        out += cjk(sample)
        out += "windows-1252"
        out += "ISO-8859-1"
        return out
    }

    /** Text in UTF-16 has zero high bytes for ASCII: mostly at odd offsets (LE) or even ones (BE). */
    private fun utf16(b: ByteArray): String? {
        if (b.size < 4) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in b.indices) if (b[i].toInt() == 0) { if (i % 2 == 0) evenZeros++ else oddZeros++ }
        val half = b.size / 2
        return when {
            oddZeros > half * 0.3 && evenZeros < half * 0.05 -> "UTF-16LE"
            evenZeros > half * 0.3 && oddZeros < half * 0.05 -> "UTF-16BE"
            else -> null
        }
    }

    private val cjkCharsets = listOf("GB18030", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR")

    private fun cjk(b: ByteArray): List<String> {
        var high = 0
        var paired = 0
        for (i in b.indices) {
            if (b[i].toInt() and 0x80 == 0) continue
            high++
            val prevHigh = i > 0 && b[i - 1].toInt() and 0x80 != 0
            val nextHigh = i + 1 < b.size && b[i + 1].toInt() and 0x80 != 0
            if (prevHigh || nextHigh) paired++
        }
        // Latin text has isolated accented bytes; CJK text is almost all multibyte runs.
        if (high < 4 || paired < high * 0.7) return emptyList()
        return cjkCharsets.mapNotNull { name ->
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: return@mapNotNull null
            if (!TextCodec.decodesCleanly(b, 0, cs, complete = b.size < SAMPLE_BYTES)) return@mapNotNull null
            val text = String(b, cs)
            val nonAscii = text.count { it.code >= 0x80 }.coerceAtLeast(1)
            val native = text.count { isNative(name, it) }
            // Han alone is ambiguous (Shift_JIS bytes also decode as GB18030 Han), but Japanese text
            // nearly always has kana and Korean text hangul, so those decodings get a bonus.
            val bonus = when (name) {
                "Shift_JIS", "EUC-JP" -> text.count { it.code in 0x3040..0x30FF }
                "EUC-KR" -> text.count { it.code in 0xAC00..0xD7AF }
                else -> 0
            }
            val fraction = native.toDouble() / nonAscii
            name to (if (fraction > 0.6) fraction + bonus.toDouble() / nonAscii else 0.0)
        }.filter { it.second > 0.6 }.sortedByDescending { it.second }.map { it.first }
    }

    private fun isNative(charset: String, c: Char): Boolean {
        val code = c.code
        val han = code in 0x4E00..0x9FFF || code in 0x3000..0x303F || code in 0xFF01..0xFF60
        return when (charset) {
            "Shift_JIS", "EUC-JP" -> han || code in 0x3040..0x30FF
            "EUC-KR" -> code in 0xAC00..0xD7AF || code in 0x3130..0x318F || han
            else -> han
        }
    }
}
