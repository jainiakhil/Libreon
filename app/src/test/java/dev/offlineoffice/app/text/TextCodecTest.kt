// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextCodecTest {
    /** Stands in for ICU (not available on the JVM) and answers like it does for Latin text. */
    private val latinDetector: (ByteArray) -> List<String> = { listOf("ISO-8859-1") }

    private fun fixture(name: String): ByteArray {
        val candidates = listOf(File("../testdata", name), File("testdata", name))
        return candidates.first { it.exists() }.readBytes()
    }

    private fun roundTrip(bytes: ByteArray, detector: (ByteArray) -> List<String> = { emptyList() }): DecodedText {
        val decoded = TextCodec.decode(bytes, detector = detector)
        assertArrayEquals("unedited text must round-trip byte for byte", bytes, TextCodec.encode(decoded.text, decoded.format, decoded.terminators))
        return decoded
    }

    @Test fun utf16leWithBomRoundTrips() {
        val d = roundTrip(fixture("utf16le-bom.txt"))
        assertEquals(TextFormat("UTF-16LE", bom = true, lineEnding = LineEnding.CRLF), d.format)
        assertTrue(d.text.startsWith("Hello, wörld"))
        assertTrue(d.text.contains("日本語"))
        assertFalse(d.text.contains('\r'))
        assertFalse(d.text.startsWith("﻿"))
    }

    @Test fun utf8WithBomRoundTrips() {
        val d = roundTrip(fixture("utf8-bom.txt"))
        assertEquals(TextFormat("UTF-8", bom = true, lineEnding = LineEnding.LF), d.format)
        assertTrue(d.text.startsWith("Café"))
        assertTrue(d.text.contains("😀"))
    }

    @Test fun latin1IsDetectedAndRoundTrips() {
        val d = roundTrip(fixture("latin1.txt"), latinDetector)
        assertEquals("ISO-8859-1", d.format.charset)
        assertFalse(d.format.bom)
        assertTrue(d.text.contains("déjà vu"))
        assertFalse(d.lossy)
    }

    @Test fun crOnlyAndCrlfArePreserved() {
        val cr = roundTrip(fixture("cr-only.txt"))
        assertEquals(LineEnding.CR, cr.format.lineEnding)
        assertEquals("Classic Mac line one\nline two\nline three\n", cr.text)
        assertEquals(LineEnding.CRLF, roundTrip(fixture("crlf-utf8.txt")).format.lineEnding)
    }

    @Test fun editedTextKeepsFormat() {
        val d = TextCodec.decode(fixture("utf16le-bom.txt"))
        val edited = d.text.replace("Last line", "Last line, edited\nplus a new one")
        val bytes = TextCodec.encode(edited, d.format, d.terminators)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), bytes.copyOf(2))
        val text = String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        assertTrue(text.contains("edited\r\nplus a new one\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'))
    }

    @Test fun mixedLineEndingsKeepEachTerminator() {
        val bytes = "a\r\nb\nc\rd\r\n".toByteArray()
        val d = roundTrip(bytes)
        assertEquals(LineEnding.MIXED, d.format.lineEnding)
        assertEquals("a\nb\nc\nd\n", d.text)
        // New lines take the most common terminator (CRLF here).
        val appended = TextCodec.encode(d.text + "e\n", d.format, d.terminators)
        assertEquals("a\r\nb\nc\rd\r\ne\r\n", String(appended))
    }

    @Test fun noLineBreakUsesRememberedStyle() {
        val hint = TextFormat("UTF-8", false, LineEnding.CRLF)
        val d = TextCodec.decode("single".toByteArray(), hint)
        assertEquals(LineEnding.CRLF, d.format.lineEnding)
        assertEquals("one\r\ntwo", String(TextCodec.encode("one\ntwo", d.format)))
    }

    @Test fun rememberedCharsetWinsOverDetectionWhenItStillFits() {
        // Pure ASCII would be detected as UTF-8; a tab that was ISO-8859-1 must stay ISO-8859-1.
        val hint = TextFormat("ISO-8859-1", false, LineEnding.LF)
        assertEquals("ISO-8859-1", TextCodec.decode("plain ascii\n".toByteArray(), hint).format.charset)
        // A BOM always wins.
        assertEquals("UTF-16LE", TextCodec.decode(fixture("utf16le-bom.txt"), hint).format.charset)
    }

    @Test fun invalidUtf8WithoutDetectorFallsBackLossy() {
        val d = TextCodec.decode(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
        assertEquals("UTF-8", d.format.charset)
        assertTrue(d.lossy)
    }

    @Test fun unmappableCharacterIsReported() {
        try {
            TextCodec.encode("price: 5 €", TextFormat("ISO-8859-1", false, LineEnding.LF))
            fail("expected UnmappableTextException")
        } catch (e: UnmappableTextException) {
            assertEquals("€", e.char)
        }
    }

    @Test fun bomDetection() {
        assertEquals("UTF-32LE" to 4, TextCodec.detectBom(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0, 'a'.code.toByte(), 0, 0, 0)))
        assertEquals("UTF-16BE" to 2, TextCodec.detectBom(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 'a'.code.toByte())))
        assertNull(TextCodec.detectBom("abc".toByteArray()))
    }

    @Test fun truncatedUtf8PrefixIsStillUtf8() {
        val bytes = "ééé".toByteArray()
        val prefix = bytes.copyOf(bytes.size - 1) // cuts the last character in half
        assertTrue(TextCodec.decodesCleanly(prefix, 0, Charsets.UTF_8, complete = false))
        assertFalse(TextCodec.decodesCleanly(prefix, 0, Charsets.UTF_8, complete = true))
    }

    @Test fun guesserHandlesLatinUtf16AndCjk() {
        val latin = TextCodec.decode(fixture("latin1.txt"), detector = CharsetGuesser::candidates)
        assertEquals("ISO-8859-1", latin.format.charset) // no 0x80–0x9F bytes, so not windows-1252
        val cp1252 = TextCodec.decode("“quoted” – 5 €\n".toByteArray(charset("windows-1252")), detector = CharsetGuesser::candidates)
        assertEquals("windows-1252", cp1252.format.charset)
        assertEquals("“quoted” – 5 €\n", cp1252.text)
        assertTrue(latin.text.contains("déjà vu"))
        val utf16 = "plain text without a BOM\nsecond line\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("UTF-16LE", TextCodec.decode(utf16, detector = CharsetGuesser::candidates).format.charset)
        val sjis = "日本語のテキストです。これはテストです。".toByteArray(charset("Shift_JIS"))
        val decoded = TextCodec.decode(sjis, detector = CharsetGuesser::candidates)
        assertEquals("日本語のテキストです。これはテストです。", decoded.text)
        // Anything else still round-trips via ISO-8859-1 instead of being replaced.
        val junk = byteArrayOf(0x81.toByte(), 'a'.code.toByte(), 0x8D.toByte())
        assertArrayEquals(junk, TextCodec.decode(junk, detector = CharsetGuesser::candidates).let { TextCodec.encode(it.text, it.format) })
    }
}
