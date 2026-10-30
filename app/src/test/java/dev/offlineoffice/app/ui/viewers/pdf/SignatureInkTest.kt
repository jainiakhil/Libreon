// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.pdf

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureInkTest {
    private val ink = SignatureInk(
        width = 900f, height = 300f, argb = 0xFF1A3FB0.toInt(),
        strokes = listOf(floatArrayOf(10f, 20f, 30.25f, 40.5f, 50f, 45f), floatArrayOf(100f, 100f)),
    )

    @Test fun jsonRoundTrip() {
        val text = ink.toJson().toString()
        assertEquals(ink, SignatureInk.fromJson(JSONObject(text)))
    }

    @Test fun jsonRoundsToHundredths() {
        val odd = ink.copy(strokes = listOf(floatArrayOf(1.23456f, 2.98765f)))
        val back = SignatureInk.fromJson(JSONObject(odd.toJson().toString()))
        assertArrayEquals(floatArrayOf(1.23f, 2.99f), back.strokes[0], 1e-4f)
    }

    @Test fun boundsCoverAllStrokes() {
        assertArrayEquals(floatArrayOf(10f, 20f, 100f, 100f), ink.bounds(), 0f)
        assertNull(ink.copy(strokes = emptyList()).bounds())
    }

    @Test fun quadSegmentsPassThroughMidpointsAndEndOnLastPoint() {
        val q = InkSmoothing.quadSegments(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f))
        // start, then (ctrl=(10,0), end=mid((10,0),(10,10))=(10,5)), then finish on (10,10)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 5f, 10f, 10f, 10f, 10f), q, 0f)
    }

    @Test fun quadSegmentsHandleShortStrokes() {
        assertEquals(0, InkSmoothing.quadSegments(FloatArray(0)).size)
        assertArrayEquals(floatArrayOf(3f, 4f), InkSmoothing.quadSegments(floatArrayOf(3f, 4f)), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 5f, 5f), InkSmoothing.quadSegments(floatArrayOf(0f, 0f, 5f, 5f)), 0f)
    }
}
