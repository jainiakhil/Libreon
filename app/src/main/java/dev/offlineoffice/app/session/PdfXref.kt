// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import java.io.File
import java.io.RandomAccessFile

/**
 * Decides whether a PDF can be saved incrementally. PDFium appends a cross-reference stream
 * without the required /Type /XRef entry when the source itself ends in a cross-reference
 * stream, which strict readers reject, so incremental saves are only used for files whose
 * last section is a classic `xref` table.
 */
object PdfXref {
    private const val TAIL_BYTES = 2048

    fun endsWithClassicXref(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val tailSize = minOf(length, TAIL_BYTES.toLong()).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(length - tailSize)
            raf.readFully(tail)
            val offset = lastStartXref(tail) ?: return false
            if (offset < 0 || offset >= length) return false
            val head = ByteArray(minOf(16L, length - offset).toInt())
            raf.seek(offset)
            raf.readFully(head)
            isClassicXrefAt(head)
        }
    }.getOrDefault(false)

    /** The offset after the last `startxref` keyword in [tail], or null. */
    fun lastStartXref(tail: ByteArray): Long? {
        val text = String(tail, Charsets.ISO_8859_1)
        val at = text.lastIndexOf("startxref")
        if (at < 0) return null
        return Regex("""\s*(\d+)""").find(text, at + "startxref".length)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** True if [bytes] (read at the cross-reference offset) start with the `xref` keyword. */
    fun isClassicXrefAt(bytes: ByteArray): Boolean =
        String(bytes, Charsets.ISO_8859_1).trimStart().startsWith("xref")
}
