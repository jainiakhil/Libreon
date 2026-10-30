// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Line terminator style of a text file. [MIXED] files keep each line's original terminator. */
enum class LineEnding(val sequence: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
    MIXED("\n", "Mixed");

    companion object {
        fun fromSequence(s: String): LineEnding = when (s) {
            "\r\n" -> CRLF
            "\r" -> CR
            else -> LF
        }
    }
}

/** How a text file is stored on disk. Saving writes exactly this back, so nothing is converted silently. */
data class TextFormat(val charset: String, val bom: Boolean, val lineEnding: LineEnding) {
    val label: String get() = buildString {
        append(charset)
        if (bom) append(" BOM")
        append(" · ")
        append(lineEnding.label)
    }

    companion object {
        val DEFAULT = TextFormat("UTF-8", bom = false, lineEnding = LineEnding.LF)
    }
}

/**
 * Decoded file contents. [text] always uses "\n" line breaks; [terminators] holds the original
 * terminator of each line when the file mixes styles, so an unedited file round-trips byte for byte.
 * [lossy] means some bytes could not be decoded and were replaced (saving would change them).
 */
class DecodedText(val text: String, val format: TextFormat, val terminators: List<String>?, val lossy: Boolean)

/** Thrown when the text contains a character the file's encoding cannot represent. */
class UnmappableTextException(val charset: String, val char: String) :
    CharacterCodingException() {
    override val message: String get() = "\"$char\" cannot be saved in $charset. Change the encoding to UTF-8 first."
}

/** Detects encoding (BOM, then strict UTF-8, then a statistical detector) and line endings. */
object TextCodec {
    private const val CR = '\r'
    private const val LF = '\n'

    /** Charset and BOM length if [bytes] starts with a Unicode byte-order mark. */
    fun detectBom(bytes: ByteArray): Pair<String, Int>? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> "UTF-8" to 3
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0 && at(3) == 0 -> "UTF-32LE" to 4
            at(0) == 0 && at(1) == 0 && at(2) == 0xFE && at(3) == 0xFF -> "UTF-32BE" to 4
            at(0) == 0xFF && at(1) == 0xFE -> "UTF-16LE" to 2
            at(0) == 0xFE && at(1) == 0xFF -> "UTF-16BE" to 2
            else -> null
        }
    }

    fun bomBytes(charset: String): ByteArray = when (charset.uppercase()) {
        "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        "UTF-32LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)
        "UTF-32BE" -> byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte())
        else -> ByteArray(0)
    }

    /**
     * True if [bytes] (from [offset]) decode in [charset] without errors. With [complete] false the
     * bytes are a prefix of the file, so an incomplete sequence at the very end is tolerated.
     */
    fun decodesCleanly(bytes: ByteArray, offset: Int, charset: Charset, complete: Boolean): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        val out = CharBuffer.allocate(8192)
        while (true) {
            val result = decoder.decode(input, out, complete)
            if (result.isError) return false
            if (result.isOverflow) { out.clear(); continue }
            break
        }
        if (complete) {
            val result = decoder.flush(out)
            if (result.isError) return false
        }
        return true
    }

    /**
     * Picks the charset for [bytes]: BOM, then [hint] (the charset remembered for this tab) if it
     * still fits, then UTF-8 if valid, then [detector]'s candidates in order, then UTF-8.
     * Returns the charset name, the BOM length and whether decoding is lossless.
     */
    fun detectCharset(
        bytes: ByteArray,
        complete: Boolean,
        hint: TextFormat?,
        detector: (ByteArray) -> List<String>,
    ): Triple<String, Int, Boolean> {
        detectBom(bytes)?.let { (name, len) -> return Triple(name, len, true) }
        hint?.takeIf { !it.bom }?.charset?.let { name ->
            val cs = charsetOrNull(name)
            if (cs != null && decodesCleanly(bytes, 0, cs, complete)) return Triple(cs.name(), 0, true)
        }
        // NUL bytes are valid UTF-8 but almost always mean UTF-16/32 without a BOM: ask the detector first.
        val candidates = detector(bytes)
        if (bytes.any { it.toInt() == 0 }) {
            candidates.firstOrNull { it.startsWith("UTF-16") || it.startsWith("UTF-32") }?.let { name ->
                val cs = charsetOrNull(name)
                if (cs != null && decodesCleanly(bytes, 0, cs, complete)) return Triple(cs.name(), 0, true)
            }
        }
        if (decodesCleanly(bytes, 0, StandardCharsets.UTF_8, complete)) return Triple("UTF-8", 0, true)
        for (candidate in candidates) {
            val cs = charsetOrNull(candidate) ?: continue
            if (cs.name() == "UTF-8") continue
            if (decodesCleanly(bytes, 0, cs, complete)) return Triple(cs.name(), 0, true)
        }
        return Triple("UTF-8", 0, false)
    }

    fun decode(bytes: ByteArray, hint: TextFormat? = null, detector: (ByteArray) -> List<String> = { emptyList() }): DecodedText {
        val (name, bomLen, clean) = detectCharset(bytes, complete = true, hint = hint, detector = detector)
        val raw = String(bytes, bomLen, bytes.size - bomLen, Charset.forName(name))
        val (text, terminators) = splitLineEndings(raw)
        val ending = when {
            terminators.isEmpty() -> hint?.lineEnding?.takeIf { it != LineEnding.MIXED } ?: LineEnding.LF
            terminators.all { it == terminators[0] } -> LineEnding.fromSequence(terminators[0])
            else -> LineEnding.MIXED
        }
        return DecodedText(
            text = text,
            format = TextFormat(name, bomLen > 0, ending),
            terminators = terminators.takeIf { ending == LineEnding.MIXED },
            lossy = !clean,
        )
    }

    /** Line ending style of a decoded sample (for large files that are never fully decoded). */
    fun detectLineEnding(sample: String): LineEnding {
        val terminators = splitLineEndings(sample).second
        return when {
            terminators.isEmpty() -> LineEnding.LF
            terminators.all { it == terminators[0] } -> LineEnding.fromSequence(terminators[0])
            else -> LineEnding.MIXED
        }
    }

    /** Normalises every terminator to "\n" and returns the original terminators in order. */
    fun splitLineEndings(raw: String): Pair<String, List<String>> {
        val terminators = ArrayList<String>()
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == CR && i + 1 < raw.length && raw[i + 1] == LF -> { terminators += "\r\n"; sb.append(LF); i += 2; continue }
                c == CR -> { terminators += "\r"; sb.append(LF) }
                c == LF -> { terminators += "\n"; sb.append(LF) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString() to terminators
    }

    /**
     * Encodes [text] (any line breaks) in [format]. For [LineEnding.MIXED], line i gets
     * [terminators]`[i]` and new lines get the most common original terminator.
     */
    fun encode(text: CharSequence, format: TextFormat, terminators: List<String>? = null): ByteArray {
        val normalized = splitLineEndings(text.toString()).first
        val withEndings = when (format.lineEnding) {
            LineEnding.LF -> normalized
            LineEnding.CRLF, LineEnding.CR -> normalized.replace("\n", format.lineEnding.sequence)
            LineEnding.MIXED -> {
                val list = terminators.orEmpty()
                val dominant = list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "\n"
                val sb = StringBuilder(normalized.length + list.size)
                var line = 0
                for (c in normalized) {
                    if (c == LF) sb.append(list.getOrElse(line++) { dominant }) else sb.append(c)
                }
                sb.toString()
            }
        }
        val charset = Charset.forName(format.charset)
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val body = try {
            val buf = encoder.encode(CharBuffer.wrap(withEndings))
            ByteArray(buf.remaining()).also { buf.get(it) }
        } catch (e: CharacterCodingException) {
            val bad = withEndings.firstOrNull { !encoder.canEncode(it) }?.toString() ?: "?"
            throw UnmappableTextException(format.charset, bad)
        }
        return if (format.bom) bomBytes(format.charset) + body else body
    }

    fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
