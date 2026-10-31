// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libreoffice.androidlib.COWebViewClient
import org.libreoffice.androidlib.LOActivity

/**
 * One Word, Excel or PowerPoint document open in the engine and shown in [webView] through
 * the Collabora front-end.
 *
 * The engine edits [file] in place. It holds one document at a time, so the previous
 * document must be [close]d before the next is [open]ed.
 */
class OfficeDocument(context: Context, val file: File) {

    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val host = MutableContextWrapper(appContext)
    private val socket = FakeSocket()
    private val pendingSave = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val saveLock = Mutex()

    @Volatile
    private var greeted = false

    @Volatile
    private var closed = false

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The engine's own modified flag: set by the first edit, cleared by a save. */
    private val _modified = MutableStateFlow(false)
    val modified: StateFlow<Boolean> = _modified.asStateFlow()

    /** The user pressed Save inside the front-end. */
    private val _saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveRequests: SharedFlow<Unit> = _saveRequests.asSharedFlow()

    private val bridge = LOActivity(object : LOActivity.Listener {
        override fun onEngineMessage(message: ByteArray) = deliver(message)
        override fun onFrontEndMessage(message: String): Boolean = handleFrontEnd(message)
    })

    /** Created on the main thread by [open]; reparented as tabs come and go, never recreated. */
    lateinit var webView: WebView
        private set

    /** Starts loading. Progress and failures are reported through [state]. */
    suspend fun open(darkTheme: Boolean) {
        OfficeEngine.prepare(appContext)
        val notebookbar = appContext.resources.configuration.smallestScreenWidthDp >= 600
        withContext(Dispatchers.Main) { webView = createWebView() }
        val fileUrl = Uri.fromFile(file).toString()
        OfficeEngine.call {
            bridge.createCOOLWSD(
                appContext.applicationInfo.dataDir, appContext.cacheDir.absolutePath,
                appContext.packageResourcePath, appContext.assets, fileUrl,
                if (notebookbar) "notebookbar" else "classic", USER_NAME,
            )
        }
        val page = Uri.parse(FRONT_END).buildUpon()
            .appendQueryParameter("file_path", fileUrl)
            .appendQueryParameter("lang", "en-US")
            .appendQueryParameter("permission", "edit")
            .apply { if (notebookbar) appendQueryParameter("userinterfacemode", "notebookbar") }
            .apply { if (darkTheme) appendQueryParameter("darkTheme", "true") }
            .build().toString()
        withContext(Dispatchers.Main) { webView.loadUrl(page) }
    }

    /** Hosts [webView] in [context] (an Activity, so the page can show popups and the keyboard). */
    fun attachTo(context: Context) {
        host.baseContext = context
        (webView.parent as? ViewGroup)?.removeView(webView)
        bridge.hardwareKeyboard = context.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
    }

    fun detach() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.baseContext = appContext
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

    /** Closes the document in the engine and releases the page. */
    suspend fun close() {
        if (closed) return
        closed = true
        // "BYE" waits for the engine to close the document, which only happens once the
        // front-end has connected; before that the engine would wait forever.
        try {
            if (greeted) OfficeEngine.call { bridge.postMobileMessageNative("BYE") }
        } finally {
            withContext(Dispatchers.Main) {
                if (::webView.isInitialized) {
                    detach()
                    webView.destroy()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return WebView(host).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The front-end comes from file:///android_asset, which these do not affect.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = COWebViewClient(socket)
            addJavascriptInterface(bridge, "COOLMessageHandler")
        }
    }

    /** Engine to front-end: queue the message and have the page fetch the queue. */
    private fun deliver(message: ByteArray) {
        if (closed) return
        socket.enqueue(message)
        when (val event = EngineMessages.parse(message)) {
            is EngineEvent.Modified -> _modified.value = event.modified
            is EngineEvent.SaveResult -> pendingSave.get()?.complete(event.success)
            EngineEvent.Loaded -> _state.compareAndSet(State.Loading, State.Ready)
            is EngineEvent.LoadFailed -> _state.value = State.Failed("The engine could not open this document (${event.kind})")
            null -> Unit
        }
        webView.post { if (!closed) webView.evaluateJavascript("window.socket.doSend();", null) }
    }

    /** Front-end to engine. Returns true to pass the message on. */
    private fun handleFrontEnd(message: String): Boolean {
        val command = message.substringBefore(' ')
        val argument = message.substringAfter(' ', "")
        when (command) {
            "HULLO" -> greeted = true
            "SAVE" -> _saveRequests.tryEmit(Unit)
            "hideProgressbar" -> _state.compareAndSet(State.Loading, State.Ready)
            "LIGHT_SCREEN", "DIM_SCREEN" -> webView.post { webView.keepScreenOn = command == "LIGHT_SCREEN" }
            // Not offered yet: printing, export, and opening links (the app is offline).
            "PRINT", "downloadas", "exportfile", "HYPERLINK", "REQUESTFILECOPY" ->
                Log.i(TAG, "ignored front-end request: $command $argument")
        }
        // Closing is ours to do ([close]); the rest only concern upstream's own Activity.
        return command !in setOf(
            "BYE", "PRINT", "SAVE", "downloadas", "exportfile", "DIM_SCREEN", "LIGHT_SCREEN",
            "MOBILEWIZARD", "HYPERLINK", "EDITMODE", "SLIDESHOW", "hideProgressbar", "REQUESTFILECOPY",
        )
    }

    private companion object {
        const val TAG = "OfficeDocument"
        const val FRONT_END = "file:///android_asset/dist/cool.html"
        const val USER_NAME = "Me"
        const val SAVE_TIMEOUT_MS = 120_000L
    }
}
