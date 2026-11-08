// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.libreoffice.androidlib.COWebViewClient
import org.libreoffice.androidlib.LOActivity

/**
 * One Word, Excel or PowerPoint document open in the engine and shown in [webView] through
 * the Collabora front-end. Lives in the `:office` process ([OfficeService]); create it on
 * the main thread.
 *
 * The engine edits [file] in place. On Android the engine cannot close a document without
 * ending its process, so there is no close: the process is ended instead ([OfficeClient.close]).
 */
internal class OfficeDocument(private val context: Context, val file: File) {

    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val socket = FakeSocket()
    private val pendingSave = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingSelection = AtomicReference<CompletableDeferred<String>?>(null)
    private val saveLock = Mutex()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The engine's own modified flag: set by the first edit, cleared by a save. */
    private val _modified = MutableStateFlow(false)
    val modified: StateFlow<Boolean> = _modified.asStateFlow()

    /** The user pressed Save inside the front-end. */
    private val _saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveRequests: SharedFlow<Unit> = _saveRequests.asSharedFlow()

    /** Writer's live word count line (null until the engine reports one). */
    private val _wordCount = MutableStateFlow<String?>(null)
    val wordCount: StateFlow<String?> = _wordCount.asStateFlow()

    /** Match counts of find-all searches (0 when nothing matched). */
    private val _searchResults = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val searchResults: SharedFlow<Int> = _searchResults.asSharedFlow()

    private val bridge = LOActivity(object : LOActivity.Listener {
        override fun onEngineMessage(message: ByteArray) = deliver(message)
        override fun onFrontEndMessage(message: String): Boolean = handleFrontEnd(message)
    })

    /** Shown in whatever window currently hosts it; never recreated. */
    val webView: WebView = createWebView()

    var hardwareKeyboard: Boolean
        get() = bridge.hardwareKeyboard
        set(value) {
            bridge.hardwareKeyboard = value
        }

    /** Starts loading. Progress and failures are reported through [state]. */
    suspend fun open(darkTheme: Boolean, readOnly: Boolean) {
        this.darkTheme = darkTheme
        OfficeEngine.prepare(context)
        val notebookbar = context.resources.configuration.smallestScreenWidthDp >= 600
        val fileUrl = Uri.fromFile(file).toString()
        OfficeEngine.call {
            bridge.createCOOLWSD(
                context.applicationInfo.dataDir, context.cacheDir.absolutePath,
                context.packageResourcePath, context.assets, fileUrl,
                if (notebookbar) "notebookbar" else "classic", USER_NAME,
            )
        }
        val page = Uri.parse(FRONT_END).buildUpon()
            .appendQueryParameter("file_path", fileUrl)
            .appendQueryParameter("lang", "en-US")
            .appendQueryParameter("permission", if (readOnly) "readonly" else "edit")
            .apply { if (notebookbar) appendQueryParameter("userinterfacemode", "notebookbar") }
            .apply { if (darkTheme) appendQueryParameter("darkTheme", "true") }
            .build().toString()
        webView.loadUrl(page)
    }

    private var darkTheme: Boolean? = null

    /** Switches the page between light and dark, as the front-end expects from its host. */
    fun setDarkTheme(dark: Boolean) {
        if (darkTheme == dark) return
        darkTheme = dark
        deliver((if (dark) "mobile: darkthemeactivated" else "mobile: lightthemeactivated").toByteArray())
    }

    /** Saves the document to [file], in its own format. */
    suspend fun save() = saveLock.withLock {
        check(_state.value == State.Ready) { "The document is not open" }
        val result = CompletableDeferred<Boolean>()
        pendingSave.set(result)
        try {
            OfficeEngine.call { bridge.postMobileMessageNative("save dontTerminateEdit=1 dontSaveIfUnmodified=0 extendedData=") }
            val ok = withTimeout(SAVE_TIMEOUT_MS) { result.await() }
            if (!ok) throw IOException("The document could not be saved")
        } finally {
            pendingSave.compareAndSet(result, null)
        }
    }

    /**
     * Finds [query] through the front-end's own search (which scrolls to and selects the match).
     * With [all] every match is highlighted and counted, reported through [searchResults].
     */
    fun find(query: String, backward: Boolean, all: Boolean) {
        val js = "app.searchService.search(${JSONObject.quote(query)}, $backward, '', ${if (all) 1 else 0})"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** Drops search highlights. */
    fun clearFind() = webView.post { webView.evaluateJavascript("app.searchService.resetSelection()", null) }

    /** The current selection as plain text ("" when nothing is selected). */
    suspend fun selectedText(): String {
        check(_state.value == State.Ready) { "The document is still loading" }
        val result = CompletableDeferred<String>()
        pendingSelection.getAndSet(result)?.cancel()
        try {
            OfficeEngine.call { bridge.postMobileMessageNative("gettextselection mimetype=text/plain;charset=utf-8") }
            return withTimeout(SELECTION_TIMEOUT_MS) { result.await() }
        } finally {
            pendingSelection.compareAndSet(result, null)
        }
    }

    /** Zooms the page the way the front-end's own zoom buttons do ([OfficeProtocol.ZOOM_IN] etc.). */
    fun zoom(step: Int) {
        val action = when (step) {
            OfficeProtocol.ZOOM_IN -> "zoomin"
            OfficeProtocol.ZOOM_OUT -> "zoomout"
            else -> "fitwidthzoom"
        }
        webView.post { webView.evaluateJavascript("app.dispatcher.dispatch('$action')", null) }
    }

    /** Writes the document as a PDF to [target], leaving the document itself as it is. */
    suspend fun exportPdf(target: File) {
        check(_state.value == State.Ready) { "The document is not open" }
        target.delete()
        OfficeEngine.call { bridge.saveAs(Uri.fromFile(target).toString(), "pdf", null) }
        if (target.length() == 0L) throw IOException("The PDF could not be written")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The front-end comes from file:///android_asset, which these do not affect.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = COWebViewClient(socket)
            addJavascriptInterface(bridge, "COOLMessageHandler")
            // Looked up per touch: the listener is initialised after this view.
            setOnTouchListener { v, e -> longPressSelects.onTouch(v, e) }
        }
    }

    /** The front-end is in edit mode (it says so with EDITMODE messages). */
    @Volatile private var editing = false

    /**
     * In view mode the front-end selects a word on a double-tap and ignores a long-press, which
     * is where Android users try first. A long-press there is turned into a double-tap.
     */
    private val longPressSelects = object : View.OnTouchListener {
        private var pressedAt: PointF? = null
        private var synthesizing = false
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                pressedAt = PointF(e.x, e.y)
            }
        })

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (synthesizing) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pressedAt = null
            detector.onTouchEvent(event)
            val at = pressedAt
            if (event.actionMasked == MotionEvent.ACTION_UP && at != null && !editing) {
                pressedAt = null
                v.post { doubleTap(v, at) }
            }
            return false
        }

        private fun doubleTap(v: View, at: PointF) {
            synthesizing = true
            val t = SystemClock.uptimeMillis()
            for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 40L to MotionEvent.ACTION_UP, 120L to MotionEvent.ACTION_DOWN, 160L to MotionEvent.ACTION_UP)) {
                val down = if (offset < 100) t else t + 120
                val e = MotionEvent.obtain(down, t + offset, action, at.x, at.y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                v.dispatchTouchEvent(e)
                e.recycle()
            }
            synthesizing = false
        }
    }

    /** Engine to front-end: queue the message and have the page fetch the queue. */
    private fun deliver(message: ByteArray) {
        socket.enqueue(message)
        when (val event = EngineMessages.parse(message)) {
            is EngineEvent.Modified -> _modified.value = event.modified
            is EngineEvent.SaveResult -> pendingSave.get()?.complete(event.success)
            EngineEvent.Loaded -> _state.compareAndSet(State.Loading, State.Ready)
            is EngineEvent.LoadFailed -> _state.value = State.Failed("The engine could not open this document (${event.kind})")
            is EngineEvent.WordCount -> _wordCount.value = event.text
            is EngineEvent.SearchResults -> _searchResults.tryEmit(event.count)
            EngineEvent.SearchNotFound -> _searchResults.tryEmit(0)
            is EngineEvent.TextSelection -> pendingSelection.get()?.complete(event.text)
            null -> Unit
        }
        webView.post { webView.evaluateJavascript("window.socket.doSend();", null) }
    }

    /** Front-end to engine. Returns true to pass the message on. */
    private fun handleFrontEnd(message: String): Boolean {
        val command = message.substringBefore(' ')
        when (command) {
            // The page's own Save button. The app's save (which has the engine save, then writes
            // the original) runs instead, so the message is not passed on.
            "save" -> _saveRequests.tryEmit(Unit)
            // The page closed its document view (its "close" button is hidden by branding.css,
            // but this is the safety net). The engine keeps running, so offer Reload rather than
            // leave a blank page.
            "BYE" -> _state.value = State.Failed("The document view was closed.")
            "hideProgressbar" -> _state.compareAndSet(State.Loading, State.Ready)
            "EDITMODE" -> editing = message.substringAfter(' ', "").trim() == "on"
            "LIGHT_SCREEN", "DIM_SCREEN" -> webView.post { webView.keepScreenOn = command == "LIGHT_SCREEN" }
            // Not offered yet: printing, export, and opening links (the app is offline).
            "PRINT", "downloadas", "exportfile", "HYPERLINK", "REQUESTFILECOPY" ->
                Log.i(TAG, "ignored front-end request: $message")
        }
        // Closing the document would end the process (see class comment); the rest only
        // concern upstream's own Activity.
        return command != "save" && command !in BLOCKED
    }

    private companion object {
        const val TAG = "OfficeDocument"
        const val FRONT_END = "file:///android_asset/dist/cool.html"
        const val USER_NAME = "Me"
        const val SAVE_TIMEOUT_MS = 120_000L
        const val SELECTION_TIMEOUT_MS = 10_000L
        val BLOCKED = setOf(
            "BYE", "PRINT", "SAVE", "downloadas", "exportfile", "DIM_SCREEN", "LIGHT_SCREEN",
            "MOBILEWIZARD", "HYPERLINK", "EDITMODE", "SLIDESHOW", "hideProgressbar", "REQUESTFILECOPY",
        )
    }
}
