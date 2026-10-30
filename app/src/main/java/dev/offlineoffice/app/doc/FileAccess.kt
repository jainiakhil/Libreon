// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.doc

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the provider tells us about a document. */
data class DocumentMeta(val displayName: String, val mimeType: String?, val size: Long?, val lastModified: Long?)

/** Thrown when the destination changed on disk since we opened it. */
class ExternalChangeException : IOException("The file was changed by another app since it was opened")

/**
 * All file I/O goes through the Storage Access Framework; the app holds no storage permission.
 * Private copies live in app storage under files/working/<tabId>/.
 */
class FileAccess(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun meta(uri: Uri): DocumentMeta = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        DocumentMeta(
            displayName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled",
            mimeType = resolver.getType(uri),
            size = size,
            lastModified = lastModified(uri),
        )
    }

    /** Takes a persistable grant if the provider offers one. Returns true on success. */
    fun tryPersist(uri: Uri, writable: Boolean): Boolean {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val flags = if (writable) read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION else read
        return runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess ||
            (writable && runCatching { resolver.takePersistableUriPermission(uri, read) }.isSuccess)
    }

    fun release(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.releasePersistableUriPermission(uri, flags) }
    }

    fun hasPersistedGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    fun workingDir(tabId: String): File = File(context.filesDir, "working/$tabId").apply { mkdirs() }

    /** Copies the document into private storage so it outlives a temporary grant. */
    suspend fun copyToPrivate(uri: Uri, tabId: String, displayName: String): File = withContext(Dispatchers.IO) {
        val target = File(workingDir(tabId), sanitize(displayName))
        resolver.openInputStream(uri)!!.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        target
    }

    fun deleteWorkingDir(tabId: String) {
        File(context.filesDir, "working/$tabId").deleteRecursively()
    }

    /**
     * Writes [source] over [uri] safely: the caller has already produced the complete new file
     * in private storage, so a failure here never leaves the destination half-written by us
     * beyond what the provider does. Verifies the byte count afterwards.
     *
     * If [expectedSize]/[expectedModified] are given and the destination no longer matches,
     * throws [ExternalChangeException] so the UI can offer Overwrite / Save as copy.
     */
    suspend fun safeWrite(uri: Uri, source: File, expectedSize: Long? = null, expectedModified: Long? = null) =
        withContext(Dispatchers.IO) {
            if (expectedSize != null || expectedModified != null) {
                val now = meta(uri)
                val sizeChanged = expectedSize != null && now.size != null && now.size != expectedSize
                val timeChanged = expectedModified != null && now.lastModified != null && now.lastModified != expectedModified
                if (sizeChanged || timeChanged) throw ExternalChangeException()
            }
            val written = resolver.openOutputStream(uri, "wt")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Provider refused to open $uri for writing")
            if (written != source.length()) throw IOException("Short write: $written of ${source.length()} bytes")
            // Providers such as MediaStore update their SIZE column lazily, so verify by reading back.
            val readBack = resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                }
                total
            }
            if (readBack != null && readBack != source.length()) {
                throw IOException("Verification failed: read back $readBack of ${source.length()} bytes")
            }
            written
        }

    private fun lastModified(uri: Uri): Long? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        return runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(120).ifBlank { "document" }
}
