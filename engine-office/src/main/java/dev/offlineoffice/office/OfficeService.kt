// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import android.view.SurfaceControlViewHost
import dev.offlineoffice.office.OfficeProtocol as P
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the engine and one document in the `:office` process. The document's page is drawn
 * into the app's window through a [SurfaceControlViewHost], so the app keeps its own tab bar
 * while the engine lives in a process that can be ended when the document closes.
 */
class OfficeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private var client: Messenger? = null
    private var document: OfficeDocument? = null
    private var host: SurfaceControlViewHost? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(msg: Message) {
        when (msg.what) {
            P.OPEN -> open(msg.replyTo, msg.data)
            P.ATTACH -> attach(msg.data, msg.arg1, msg.arg2)
            P.RESIZE -> host?.relayout(msg.arg1, msg.arg2)
            P.DETACH -> detach()
            P.SAVE -> save(msg.arg1)
            P.THEME -> document?.setDarkTheme(msg.arg1 == 1)
            P.FIND -> document?.find(
                msg.data.getString(P.KEY_QUERY).orEmpty(),
                backward = msg.arg1 and P.FIND_BACKWARD != 0,
                all = msg.arg1 and P.FIND_ALL != 0,
            )
            P.FIND_CLEAR -> document?.clearFind()
            P.GET_SELECTION -> selection(msg.arg1)
        }
    }

    private fun open(replyTo: Messenger, data: Bundle) {
        check(document == null) { "This process already holds a document" }
        client = replyTo
        val doc = OfficeDocument(this, File(checkNotNull(data.getString(P.KEY_PATH))))
        document = doc
        scope.launch {
            doc.state.collect { state ->
                send(P.STATE, arg1 = when (state) {
                    OfficeDocument.State.Loading -> P.STATE_LOADING
                    OfficeDocument.State.Ready -> P.STATE_READY
                    is OfficeDocument.State.Failed -> P.STATE_FAILED
                }, data = (state as? OfficeDocument.State.Failed)?.let { messageBundle(it.message) })
            }
        }
        scope.launch { doc.modified.collect { send(P.MODIFIED, arg1 = if (it) 1 else 0) } }
        scope.launch { doc.saveRequests.collect { send(P.SAVE_REQUESTED) } }
        scope.launch { doc.searchResults.collect { send(P.FOUND, arg1 = it) } }
        scope.launch { doc.wordCount.collect { if (it != null) send(P.WORD_COUNT, data = messageBundle(it)) } }
        scope.launch {
            try {
                doc.open(darkTheme = data.getBoolean(P.KEY_DARK), readOnly = data.getBoolean(P.KEY_READ_ONLY))
            } catch (e: Exception) {
                Log.e(TAG, "open", e)
                send(P.STATE, arg1 = P.STATE_FAILED, data = messageBundle(e.message ?: e.javaClass.simpleName))
            }
        }
        send(P.OPENED, arg1 = Process.myPid())
    }

    private fun attach(data: Bundle, width: Int, height: Int) {
        val doc = document ?: return
        detach()
        val display = getSystemService(DisplayManager::class.java).getDisplay(data.getInt(P.KEY_DISPLAY)) ?: return
        doc.hardwareKeyboard = data.getBoolean(P.KEY_KEYBOARD)
        val newHost = SurfaceControlViewHost(createDisplayContext(display), display, data.getBinder(P.KEY_HOST_TOKEN))
        newHost.setView(doc.webView, width, height)
        host = newHost
        send(P.SURFACE, data = Bundle().apply { putParcelable(P.KEY_SURFACE, newHost.surfacePackage) })
    }

    private fun detach() {
        host?.release()
        host = null
    }

    private fun save(requestId: Int) {
        val doc = document ?: return
        scope.launch {
            val error = runCatching { doc.save() }.exceptionOrNull()
            send(P.SAVED, arg1 = requestId, data = error?.let { messageBundle(it.message ?: it.javaClass.simpleName) })
        }
    }

    private fun selection(requestId: Int) {
        val doc = document ?: return
        scope.launch {
            val text = runCatching { doc.selectedText() }.onFailure { Log.w(TAG, "selection", it) }.getOrNull()
            send(P.SELECTION, arg1 = requestId, data = text?.let(::messageBundle))
        }
    }

    private fun send(what: Int, arg1: Int = 0, data: Bundle? = null) {
        val msg = Message.obtain(null, what, arg1, 0)
        data?.let { msg.data = it }
        runCatching { client?.send(msg) }.onFailure { Log.w(TAG, "client gone", it) }
    }

    private fun messageBundle(message: String) = Bundle().apply { putString(P.KEY_MESSAGE, message) }

    private companion object {
        const val TAG = "OfficeService"
    }
}
