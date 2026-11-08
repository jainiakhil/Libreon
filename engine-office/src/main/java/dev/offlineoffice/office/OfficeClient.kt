// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.view.SurfaceControlViewHost.SurfacePackage
import android.view.SurfaceView
import dev.offlineoffice.office.OfficeProtocol as P
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app-process side of one Office document. Starts a fresh `:office` process
 * ([OfficeService]) for the document, shows its page in a [SurfaceView] of ours, and ends
 * that process on [close]. Call everything on the main thread.
 */
class OfficeClient(context: Context) {

    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val incoming = Messenger(Handler(Looper.getMainLooper()) { onMessage(it); true })

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The engine's modified flag: set by the first edit, cleared by any save. */
    private val _modified = MutableStateFlow(false)
    val modified: StateFlow<Boolean> = _modified.asStateFlow()

    /** The user pressed Save inside the document page. */
    private val _saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveRequests: SharedFlow<Unit> = _saveRequests.asSharedFlow()

    /** Writer's live word count line, e.g. "Selected: 3 of 1,204 words, 17 of 6,880 characters". */
    private val _wordCount = MutableStateFlow<String?>(null)
    val wordCount: StateFlow<String?> = _wordCount.asStateFlow()

    /** Match counts of [find] with `all` (0: no match). */
    private val _found = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val found: SharedFlow<Int> = _found.asSharedFlow()

    private var service: Messenger? = null
    private var binder: IBinder? = null
    private var bound = false
    private var closing = false
    private var pid = 0
    private val connected = CompletableDeferred<Unit>()
    private val opened = CompletableDeferred<Unit>()
    private val died = CompletableDeferred<Unit>()
    private var surfaceView: SurfaceView? = null
    private var nextSaveId = 0
    private val saves = mutableMapOf<Int, CompletableDeferred<String?>>()
    private val selections = mutableMapOf<Int, CompletableDeferred<String?>>()

    private val death = IBinder.DeathRecipient { main.post(::onDeath) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // A service restarted after a crash is empty; onDeath has already reported it.
            if (binder != null) return
            binder = service
            service.linkToDeath(death, 0)
            this@OfficeClient.service = Messenger(service)
            connected.complete(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    /** Starts the engine process and begins loading [file], which the engine edits in place. */
    suspend fun open(file: File, darkTheme: Boolean, readOnly: Boolean = false) = withContext(Dispatchers.Main) {
        bound = app.bindService(Intent(app, OfficeService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) throw IOException("The Office engine could not be started")
        withTimeout(START_TIMEOUT_MS) { connected.await() }
        send(P.OPEN, data = Bundle().apply {
            putString(P.KEY_PATH, file.absolutePath)
            putBoolean(P.KEY_DARK, darkTheme)
            putBoolean(P.KEY_READ_ONLY, readOnly)
        })
        withTimeout(START_TIMEOUT_MS) { opened.await() }
    }

    /** Shows the page in [view]; call once its surface exists. */
    fun attach(view: SurfaceView) {
        surfaceView = view
        send(P.ATTACH, view.width, view.height, Bundle().apply {
            putBinder(P.KEY_HOST_TOKEN, view.hostToken)
            putInt(P.KEY_DISPLAY, view.display.displayId)
            putBoolean(P.KEY_KEYBOARD, view.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY)
        })
    }

    fun resize(view: SurfaceView, width: Int, height: Int) {
        if (view === surfaceView) send(P.RESIZE, width, height)
    }

    /** Follows a change of the system theme. */
    fun setDarkTheme(dark: Boolean) = send(P.THEME, if (dark) 1 else 0)

    fun detach(view: SurfaceView) {
        if (view !== surfaceView) return
        surfaceView = null
        send(P.DETACH)
    }

    /** Has the engine save the document file, in its own format. */
    suspend fun save() = withContext(Dispatchers.Main) {
        val id = ++nextSaveId
        val result = CompletableDeferred<String?>()
        saves[id] = result
        try {
            send(P.SAVE, id)
            withTimeout(SAVE_TIMEOUT_MS) { result.await() }?.let { throw IOException(it) }
        } finally {
            saves.remove(id)
        }
    }

    /**
     * Finds [query] in the document, scrolling to and selecting the next (or previous) match.
     * With [all], every match is highlighted and the count is reported through [found].
     */
    fun find(query: String, backward: Boolean = false, all: Boolean = false) {
        val flags = (if (backward) P.FIND_BACKWARD else 0) or (if (all) P.FIND_ALL else 0)
        send(P.FIND, flags, data = Bundle().apply { putString(P.KEY_QUERY, query) })
    }

    fun clearFind() = send(P.FIND_CLEAR)

    /** The current selection as plain text ("" when nothing is selected). */
    suspend fun selectedText(): String = withContext(Dispatchers.Main) {
        val id = ++nextSaveId
        val result = CompletableDeferred<String?>()
        selections[id] = result
        try {
            send(P.GET_SELECTION, id)
            withTimeout(SELECTION_TIMEOUT_MS) { result.await() } ?: throw IOException("The selection could not be read")
        } finally {
            selections.remove(id)
        }
    }

    fun zoomIn() = send(P.ZOOM, P.ZOOM_IN)
    fun zoomOut() = send(P.ZOOM, P.ZOOM_OUT)
    fun zoomToFit() = send(P.ZOOM, P.ZOOM_FIT)

    /** Has the engine write the document, with any unsaved edits, as a PDF to [target]. */
    suspend fun exportPdf(target: File) = withContext(Dispatchers.Main) {
        val id = ++nextSaveId
        val result = CompletableDeferred<String?>()
        saves[id] = result
        try {
            send(P.EXPORT_PDF, id, data = Bundle().apply { putString(P.KEY_PATH, target.absolutePath) })
            withTimeout(SAVE_TIMEOUT_MS) { result.await() }?.let { throw IOException(it) }
        } finally {
            saves.remove(id)
        }
    }

    /** Ends the engine process. Unsaved edits in it are lost; checkpoint first. */
    suspend fun close() = withContext(Dispatchers.Main) {
        if (closing) return@withContext
        closing = true
        surfaceView = null
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
        if (pid > 0) Process.killProcess(pid)
        if (binder != null) withTimeoutOrNull(EXIT_TIMEOUT_MS) { died.await() }
    }

    private fun onMessage(msg: Message) {
        when (msg.what) {
            P.OPENED -> {
                pid = msg.arg1
                opened.complete(Unit)
            }
            P.SURFACE -> {
                val surface = msg.data.getParcelable(P.KEY_SURFACE, SurfacePackage::class.java) ?: return
                surfaceView?.setChildSurfacePackage(surface)
            }
            P.STATE -> _state.value = when (msg.arg1) {
                P.STATE_READY -> State.Ready
                P.STATE_FAILED -> State.Failed(msg.data.getString(P.KEY_MESSAGE) ?: "The document could not be opened")
                else -> State.Loading
            }
            P.MODIFIED -> _modified.value = msg.arg1 == 1
            P.SAVE_REQUESTED -> _saveRequests.tryEmit(Unit)
            P.SAVED, P.EXPORTED -> saves[msg.arg1]?.complete(msg.peekData()?.getString(P.KEY_MESSAGE))
            P.FOUND -> _found.tryEmit(msg.arg1)
            P.WORD_COUNT -> _wordCount.value = msg.data.getString(P.KEY_MESSAGE)
            P.SELECTION -> selections[msg.arg1]?.complete(msg.peekData()?.getString(P.KEY_MESSAGE))
        }
    }

    private fun onDeath() {
        died.complete(Unit)
        if (closing) return
        _state.value = State.Failed("The Office engine stopped unexpectedly.")
        saves.values.forEach { it.complete("The Office engine stopped") }
        selections.values.forEach { it.complete(null) }
        opened.completeExceptionally(IOException("The Office engine stopped"))
        if (bound) runCatching { app.unbindService(connection) }
        bound = false
    }

    private fun send(what: Int, arg1: Int = 0, arg2: Int = 0, data: Bundle? = null) {
        val target = service ?: return
        val msg = Message.obtain(null, what, arg1, arg2)
        msg.replyTo = incoming
        data?.let { msg.data = it }
        runCatching { target.send(msg) }
    }

    private companion object {
        const val START_TIMEOUT_MS = 20_000L
        const val SAVE_TIMEOUT_MS = 130_000L
        const val SELECTION_TIMEOUT_MS = 15_000L
        const val EXIT_TIMEOUT_MS = 5_000L
    }
}
