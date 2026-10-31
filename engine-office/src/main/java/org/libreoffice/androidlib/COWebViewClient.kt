// SPDX-License-Identifier: GPL-3.0-or-later
package org.libreoffice.androidlib

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.offlineoffice.office.FakeSocket
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Serves the front-end's `cool://` requests (its fake websocket and embedded media) and
 * blocks everything that would leave the device. The glue's native `getEmbeddedMediaPath`
 * is bound to this class name.
 */
class COWebViewClient internal constructor(private val socket: FakeSocket) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        return when (url.scheme) {
            "cool" -> serveCool(url)
            // The front-end itself (file:///android_asset/…) and in-page data.
            "file", "data", "blob" -> null
            else -> response(403, "Offline", ByteArray(0))
        }
    }

    /** Links and redirects never navigate away from the document. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    private fun serveCool(url: Uri): WebResourceResponse {
        val path = url.path.orEmpty()
        return when {
            path == "/cool/media" -> serveMedia(url)
            path.startsWith("/cool/mobilesocket/") -> {
                // [cool, mobilesocket, cool, <wopi path>, ws, ws, <command>, open|write, <id>]
                val segments = url.pathSegments
                when {
                    segments.size < 7 -> response(400, "Bad Request", ByteArray(0))
                    segments[6] == "open" -> response(200, "OK", socket.open())
                    else -> response(200, "OK", socket.drain())
                }
            }
            else -> response(404, "Not Found", ByteArray(0))
        }
    }

    private fun serveMedia(url: Uri): WebResourceResponse {
        // Slideshow media URLs arrive with their query string encoded.
        val tag = Uri.parse(Uri.decode(url.toString())).getQueryParameter("Tag")
            ?: return response(404, "Not Found", ByteArray(0))
        val media = getEmbeddedMediaPath(tag).takeIf { it.isNotEmpty() }?.let(::File)
        if (media == null || !media.isFile) return response(404, "Not Found", ByteArray(0))
        return response(200, "OK", media.inputStream(), media.length())
    }

    private external fun getEmbeddedMediaPath(tag: String): String

    private companion object {
        fun response(code: Int, reason: String, body: ByteArray) =
            response(code, reason, ByteArrayInputStream(body), body.size.toLong())

        fun response(code: Int, reason: String, body: InputStream, length: Long) = WebResourceResponse(
            null, null, code, reason,
            // The front-end runs from a file: page, whose origin is "null".
            mapOf("Access-Control-Allow-Origin" to "null", "Content-Length" to length.toString()),
            body,
        )
    }
}
