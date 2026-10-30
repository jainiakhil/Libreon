// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import org.json.JSONArray
import org.json.JSONObject

/**
 * A drawn signature as vector strokes: each stroke is x,y pairs in pad pixels of a
 * [width] x [height] pad. Stored as our own small JSON format next to a rendered PNG.
 */
data class SignatureInk(
    val width: Float,
    val height: Float,
    val argb: Int,
    val strokes: List<FloatArray>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("width", width.toDouble())
        .put("height", height.toDouble())
        .put("color", argb)
        .put("strokes", JSONArray().apply {
            strokes.forEach { s -> put(JSONArray().apply { s.forEach { put(round2(it)) } }) }
        })

    /** Bounding box of all points as [left, top, right, bottom], or null when empty. */
    fun bounds(): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.size) {
                l = minOf(l, s[i]); r = maxOf(r, s[i]); t = minOf(t, s[i + 1]); b = maxOf(b, s[i + 1])
                i += 2
            }
        }
        return if (l > r) null else floatArrayOf(l, t, r, b)
    }

    override fun equals(other: Any?): Boolean = other is SignatureInk && width == other.width &&
        height == other.height && argb == other.argb && strokes.size == other.strokes.size &&
        strokes.indices.all { strokes[it].contentEquals(other.strokes[it]) }

    override fun hashCode(): Int = strokes.fold(argb) { h, s -> 31 * h + s.contentHashCode() }

    companion object {
        fun fromJson(json: JSONObject): SignatureInk {
            val array = json.getJSONArray("strokes")
            return SignatureInk(
                width = json.getDouble("width").toFloat(),
                height = json.getDouble("height").toFloat(),
                argb = json.getInt("color"),
                strokes = (0 until array.length()).map { i ->
                    val s = array.getJSONArray(i)
                    FloatArray(s.length()) { s.getDouble(it).toFloat() }
                },
            )
        }

        private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0
    }
}

/** Stroke smoothing shared by the live pad and the rendered PNG. */
object InkSmoothing {
    /** Points closer than this (pad pixels) to the previous one are dropped while drawing. */
    const val MIN_STEP = 1.5f

    /**
     * Turns a polyline into quadratic Bézier segments through the midpoints of consecutive
     * points, using the points themselves as control points. Returns
     * [startX, startY, (ctrlX, ctrlY, endX, endY)*]; a single point yields just the start.
     */
    fun quadSegments(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(points[0], points[1])
        if (n == 2) return floatArrayOf(points[0], points[1], points[0], points[1], points[2], points[3])
        val out = ArrayList<Float>(4 * n)
        out += points[0]; out += points[1]
        for (i in 1 until n - 1) {
            val cx = points[2 * i]; val cy = points[2 * i + 1]
            val mx = (cx + points[2 * i + 2]) / 2; val my = (cy + points[2 * i + 3]) / 2
            out += cx; out += cy; out += mx; out += my
        }
        // Finish exactly on the last point.
        val lx = points[2 * n - 2]; val ly = points[2 * n - 1]
        out += lx; out += ly; out += lx; out += ly
        return out.toFloatArray()
    }
}
