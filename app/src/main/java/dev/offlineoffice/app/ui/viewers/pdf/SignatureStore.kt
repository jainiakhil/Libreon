// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A saved signature or initials: metadata JSON plus a transparent PNG. */
data class SavedSignature(val id: String, val initials: Boolean, val png: File, val createdAt: Long)

/**
 * Saved signatures in app-private storage (`files/signatures`, excluded from backups).
 * Drawn signatures keep their vector strokes; imported images keep only the PNG.
 * Nothing here ever leaves the device.
 */
class SignatureStore(context: Context) {
    private val dir = File(context.filesDir, "signatures").apply { mkdirs() }

    suspend fun list(): List<SavedSignature> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { meta ->
            runCatching {
                val json = JSONObject(meta.readText())
                val id = meta.name.removeSuffix(".json")
                val png = File(dir, "$id.png").takeIf { it.exists() } ?: return@runCatching null
                SavedSignature(id, json.optString("kind") == "initials", png, json.optLong("created"))
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    /** Saves drawn strokes and renders them to a cropped, transparent PNG. */
    suspend fun saveInk(ink: SignatureInk, initials: Boolean): SavedSignature = withContext(Dispatchers.IO) {
        val bitmap = renderInk(ink) ?: error("empty signature")
        write(JSONObject().put("ink", ink.toJson()), bitmap, initials)
    }

    /** Imports a picked image: downscaled, near-white background made transparent. */
    suspend fun importImage(context: Context, uri: Uri, initials: Boolean): SavedSignature = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_IMPORT_PX) {
                val s = MAX_IMPORT_PX.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
            }
        }
        val bitmap = clearBackground(decoded.copy(Bitmap.Config.ARGB_8888, true))
        write(JSONObject().put("image", true), bitmap, initials)
    }

    suspend fun delete(signature: SavedSignature) = withContext(Dispatchers.IO) {
        File(dir, "${signature.id}.json").delete()
        signature.png.delete()
    }

    suspend fun loadBitmap(signature: SavedSignature): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(signature.png.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    private fun write(meta: JSONObject, bitmap: Bitmap, initials: Boolean): SavedSignature {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val png = File(dir, "$id.png")
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        meta.put("kind", if (initials) "initials" else "signature").put("created", now)
        File(dir, "$id.json").writeText(meta.toString())
        return SavedSignature(id, initials, png, now)
    }

    companion object {
        private const val MAX_IMPORT_PX = 1200
        private const val RENDER_HEIGHT_PX = 360f

        /** Stroke width as a fraction of the pad height. */
        const val STROKE_FRACTION = 0.018f

        /** Builds the smoothed path for one stroke, in pad pixels. */
        fun strokePath(points: FloatArray, path: Path = Path()): Path {
            val q = InkSmoothing.quadSegments(points)
            if (q.size < 2) return path
            path.moveTo(q[0], q[1])
            if (q.size == 2) path.lineTo(q[0] + 0.1f, q[1]) // a dot
            var i = 2
            while (i + 3 < q.size) {
                path.quadTo(q[i], q[i + 1], q[i + 2], q[i + 3])
                i += 4
            }
            return path
        }

        /** Renders [ink] cropped to its strokes onto a transparent bitmap. */
        fun renderInk(ink: SignatureInk): Bitmap? {
            val b = ink.bounds() ?: return null
            val stroke = ink.height * STROKE_FRACTION
            val pad = stroke * 2
            val w = (b[2] - b[0]) + 2 * pad
            val h = (b[3] - b[1]) + 2 * pad
            val scale = RENDER_HEIGHT_PX / maxOf(h, ink.height * 0.25f)
            val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            canvas.translate(pad - b[0], pad - b[1])
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink.argb
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            ink.strokes.forEach { canvas.drawPath(strokePath(it), paint) }
            return bitmap
        }

        /** Makes near-white pixels transparent (a signature photographed on paper). */
        private fun clearBackground(bitmap: Bitmap): Bitmap {
            val w = bitmap.width
            val row = IntArray(w)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    val lum = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)) / 255.0
                    if (lum > 0.85) row[x] = 0
                    else if (lum > 0.7) row[x] = (c and 0x00FFFFFF) or ((((0.85 - lum) / 0.15) * ((c ushr 24) and 0xFF)).toInt() shl 24)
                }
                bitmap.setPixels(row, 0, w, 0, y, w, 1)
            }
            return bitmap
        }
    }
}
