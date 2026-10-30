// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui.viewers.text

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.offlineoffice.app.text.MarkdownRenderer
import dev.offlineoffice.app.text.PreviewColors
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders Markdown in a locked-down WebView: no JavaScript, no file or content access, network
 * loads blocked, and every request other than the page itself (a data: URL) answered with an empty
 * 403, so nothing is ever fetched (the page's CSP forbids loads as well).
 * Link taps never navigate; they are handed to [onLink] (which offers to copy the URL).
 */
@Composable
fun MarkdownPreview(markdown: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val colors = PreviewColors(
        background = scheme.surface.hex(),
        text = scheme.onSurface.hex(),
        muted = scheme.onSurfaceVariant.hex(),
        border = scheme.outlineVariant.hex(),
        codeBackground = scheme.surfaceContainerHighest.hex(),
        link = scheme.primary.hex(),
        dark = dark,
    )
    val html by produceState<String?>(null, markdown, colors) {
        value = withContext(Dispatchers.Default) { MarkdownRenderer.page(markdown, colors) }
    }
    val linkHandler by rememberUpdatedState(onLink)
    val background = scheme.surface.toArgb()
    AndroidView(
        modifier = modifier.fillMaxSize().clipToBounds(),
        factory = { context -> lockedDownWebView(context) { linkHandler(it) } },
        update = { view ->
            view.setBackgroundColor(background)
            val page = html ?: return@AndroidView
            if (view.tag != page) {
                view.tag = page
                view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

@SuppressLint("SetJavaScriptEnabled") // explicitly disabled below
private fun lockedDownWebView(context: android.content.Context, onLink: (String) -> Unit) = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        domStorageEnabled = false
        safeBrowsingEnabled = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Our own page arrives as a main-frame data: URL (no network involved); block everything else.
            if (request.isForMainFrame && request.url.scheme == "data") return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            onLink(request.url.toString())
            return true
        }
    }
}

private fun Color.hex(): String = String.format("#%06X", toArgb() and 0xFFFFFF)
