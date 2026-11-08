// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.annotation.SuppressLint
import android.content.ClipboardManager
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

    /**
     * Web, e-mail and phone links the user chose to open (after the page's own "External link"
     * confirmation). The app hands them to another app; this process never goes online.
     */
    private val _linkRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val linkRequests: SharedFlow<String> = _linkRequests.asSharedFlow()

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

    /** The page is ready: adds zoom buttons to its toolbar (styled by branding.css). */
    private fun onReady() {
        if (_state.compareAndSet(State.Loading, State.Ready)) {
            addZoomButtons()
            webView.post { webView.evaluateJavascript(OPEN_LINKS_JS, null) }
        }
    }

    private fun addZoomButtons() = webView.post { webView.evaluateJavascript(ZOOM_BUTTONS_JS, null) }

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
        // A tile request the engine could not parse (seen while zooming PowerPoint slides) costs
        // only that tile, which the page asks for again, but the page would cover the document
        // with an error dialog. Keep it to the log.
        if (isRejectedTileRequest(message)) {
            Log.w(TAG, "engine rejected a tile request: ${String(message, 0, minOf(message.size, 120))}")
            return
        }
        socket.enqueue(message)
        when (val event = EngineMessages.parse(message)) {
            is EngineEvent.Modified -> _modified.value = event.modified
            is EngineEvent.SaveResult -> pendingSave.get()?.complete(event.success)
            EngineEvent.Loaded -> onReady()
            is EngineEvent.LoadFailed -> _state.value = State.Failed("The engine could not open this document (${event.kind})")
            is EngineEvent.WordCount -> _wordCount.value = event.text
            is EngineEvent.SearchResults -> _searchResults.tryEmit(event.count)
            EngineEvent.SearchNotFound -> _searchResults.tryEmit(0)
            is EngineEvent.TextSelection -> pendingSelection.get()?.complete(event.text)
            null -> Unit
        }
        webView.post { webView.evaluateJavascript("window.socket.doSend();", null) }
    }

    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** What the system clipboard held just after the last copy or cut inside this document. */
    @Volatile private var lastInternalCopy: CharSequence? = null

    /**
     * The engine pastes from its own clipboard, which only knows what was copied inside this
     * document. When the system clipboard holds something else (copied in another app), that is
     * pasted instead. Returns true when it handled the paste.
     */
    private fun pasteFromSystem(): Boolean {
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false
        val text = item.coerceToText(context)
        if (text.isNullOrEmpty() || text.toString() == lastInternalCopy?.toString()) return false
        val html = item.htmlText
        if (html != null) bridge.paste("text/html", html.toByteArray()) else bridge.paste("text/plain;charset=utf-8", text.toString().toByteArray())
        return true
    }

    private fun isRejectedTileRequest(message: ByteArray): Boolean {
        if (message.size > 120 || !message.startsWithAscii("error:")) return false
        val head = String(message)
        return ("cmd=tile " in head || "cmd=tilecombine " in head) && "kind=invalid" in head
    }

    private fun ByteArray.startsWithAscii(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    /** Front-end to engine. Returns true to pass the message on. */
    private fun handleFrontEnd(message: String): Boolean {
        val command = message.substringBefore(' ')
        if (command == "uno") {
            when (message.trim()) {
                // The copy reaches the system clipboard shortly after; remember it to recognise it.
                "uno .uno:Copy", "uno .uno:Cut" -> webView.postDelayed({
                    lastInternalCopy = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                }, CLIPBOARD_SETTLE_MS)
                "uno .uno:Paste" -> if (pasteFromSystem()) return false
            }
        }
        when (command) {
            // The page's own Save button. The app's save (which has the engine save, then writes
            // the original) runs instead, so the message is not passed on.
            "save" -> _saveRequests.tryEmit(Unit)
            // The page closed its document view (its "close" button is hidden by branding.css,
            // but this is the safety net). The engine keeps running, so offer Reload rather than
            // leave a blank page.
            "BYE" -> _state.value = State.Failed("The document view was closed.")
            "hideProgressbar" -> onReady()
            OPEN_LINK -> {
                val url = message.substringAfter(' ', "").trim()
                if (LINK_SCHEMES.any { url.startsWith(it, ignoreCase = true) }) _linkRequests.tryEmit(url)
                return false
            }
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
        const val CLIPBOARD_SETTLE_MS = 600L
        val BLOCKED = setOf(
            "BYE", "PRINT", "SAVE", "downloadas", "exportfile", "DIM_SCREEN", "LIGHT_SCREEN",
            "MOBILEWIZARD", "HYPERLINK", "EDITMODE", "SLIDESHOW", "hideProgressbar", "REQUESTFILECOPY",
        )

        /**
         * Zoom controls in the page's own toolbar, next to its (hidden in view mode) close button:
         * zoom out, the zoom level (a menu of fit width and preset levels; 100% is true size) and
         * zoom in. Zooms jump straight to the new level: the front-end's animated zoom can send
         * tile requests from an in-between zoom, which the engine rejects. Icons follow the page's
         * light or dark theme; branding.css styles them.
         */
        /** Front-end message (made up by [OPEN_LINKS_JS]) asking to open a link elsewhere. */
        const val OPEN_LINK = "LIBREON_OPENLINK"
        val LINK_SCHEMES = listOf("http://", "https://", "mailto:", "tel:")

        /**
         * The page opens links with window.open, which does nothing in a WebView. Pass web,
         * e-mail and phone links to the app instead; anything else is left as it was.
         */
        val OPEN_LINKS_JS = """
            (function () {
              if (window.__libreonLinks) return;
              window.__libreonLinks = true;
              const original = window.open;
              window.open = function (url) {
                if (typeof url === 'string' && /^(https?:|mailto:|tel:)/i.test(url.trim())) {
                  window.COOLMessageHandler.postMobileMessage('$OPEN_LINK ' + url.trim());
                  return null;
                }
                return original.apply(window, arguments);
              };
            })();
        """.trimIndent()

        val ZOOM_BUTTONS_JS = """
            (function install(attempts) {
              const after = document.getElementById('toolbar-mobile-back');
              if (document.getElementById('libreon-zoom')) return;
              // Right after loading, the toolbar or the document model may not be set up yet.
              if (!after || !app.activeDocument || !app.zoomControl) {
                if (attempts > 0) setTimeout(() => install(attempts - 1), 500);
                return;
              }
              try {
              const map = app.map;
              const zoomTo = (level) => app.zoomControl.zoomTo(
                Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), level)), undefined, false);
              // The largest zoom step at which the page fits the screen width. (The front-end's own
              // fit often lands a step lower.) A spreadsheet has no page width, so it keeps that one.
              const fitWidth = () => {
                const doc = app.activeDocument;
                if (map._docLayer._docType === 'spreadsheet' || !doc.fileSize || !doc.fileSize.pX) {
                  app.dispatcher.dispatch('fitwidthzoom');
                  return;
                }
                const screen = innerWidth * devicePixelRatio + 1;
                const current = map.getZoom();
                let best = map.getMinZoom();
                for (let z = best; z <= map.getMaxZoom(); z++) {
                  if (doc.fileSize.pX * doc.getZoomScale(z, current) <= screen) best = z;
                }
                zoomTo(best);
              };
              const box = document.createElement('div');
              box.id = 'libreon-zoom';
              const iconButton = (icon, label, run) => {
                const button = document.createElement('button');
                button.className = 'libreon-zoom-btn';
                button.title = label;
                button.setAttribute('aria-label', label);
                const img = document.createElement('img');
                img.alt = '';
                app.LOUtil.setImage(img, icon, map);
                button.appendChild(img);
                button.addEventListener('click', (e) => { e.preventDefault(); run(); });
                return button;
              };

              const level = document.createElement('button');
              level.className = 'libreon-zoom-level';
              level.setAttribute('aria-haspopup', 'menu');
              const showLevel = () => {
                level.textContent = app.activeDocument.getZoomPercent() + '%';
                level.setAttribute('aria-label', 'Zoom ' + level.textContent + '. Choose a zoom level');
              };
              map.on('zoomend', showLevel);
              showLevel();

              const menu = document.createElement('div');
              menu.id = 'libreon-zoom-menu';
              menu.setAttribute('role', 'menu');
              menu.hidden = true;
              const item = (text, run) => {
                const b = document.createElement('button');
                b.setAttribute('role', 'menuitem');
                b.textContent = text;
                b.addEventListener('click', (e) => { e.preventDefault(); menu.hidden = true; run(); });
                menu.appendChild(b);
              };
              item('Fit width', fitWidth);
              for (const percent of [50, 100, 150, 200, 400]) {
                item(percent + '%', () => zoomTo(app.activeDocument.getZoomIndex(percent)));
              }
              level.addEventListener('click', (e) => {
                e.preventDefault();
                const r = level.getBoundingClientRect();
                menu.style.left = r.left + 'px';
                menu.style.top = (r.bottom + 4) + 'px';
                menu.hidden = !menu.hidden;
              });
              document.addEventListener('pointerdown', (e) => {
                if (!menu.hidden && !menu.contains(e.target) && !level.contains(e.target)) menu.hidden = true;
              }, true);
              document.body.appendChild(menu);

              box.appendChild(iconButton('lc_zoomout.svg', 'Zoom out', () => zoomTo(map.getZoom() - 1)));
              box.appendChild(level);
              box.appendChild(iconButton('lc_zoomin.svg', 'Zoom in', () => zoomTo(map.getZoom() + 1)));
              after.after(box);
              } catch (e) {
                if (attempts > 0) setTimeout(() => install(attempts - 1), 500);
              }
            })(40)
        """.trimIndent()
    }
}
