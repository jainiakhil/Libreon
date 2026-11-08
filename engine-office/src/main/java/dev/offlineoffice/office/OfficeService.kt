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
    /** The app window and display [host] was made for; it is reused when the same window comes back. */
    private var hostToken: IBinder? = null
    private var hostDisplay = -1

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
            P.EXPORT_PDF -> exportPdf(msg.arg1, File(checkNotNull(msg.data.getString(P.KEY_PATH))))
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
        scope.launch { doc.linkRequests.collect { send(P.OPEN_LINK, data = messageBundle(it)) } }
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
        doc.hardwareKeyboard = data.getBoolean(P.KEY_KEYBOARD)
        val token = data.getBinder(P.KEY_HOST_TOKEN)
        val displayId = data.getInt(P.KEY_DISPLAY)
        try {
            // The app shows the page again in the same window (back from the background, a
            // tab switch): hand it a new package of the same surface. Moving the page into a
            // fresh host each time can leave it blank on some devices.
            val existing = host?.takeIf { token == hostToken && displayId == hostDisplay }
            if (existing != null) {
                existing.relayout(width, height)
                send(P.SURFACE, data = Bundle().apply { putParcelable(P.KEY_SURFACE, existing.surfacePackage) })
                return
            }
            releaseHost()
            val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
            val newHost = SurfaceControlViewHost(createDisplayContext(display), display, token)
            newHost.setView(doc.webView, width, height)
            host = newHost
            hostToken = token
            hostDisplay = displayId
            send(P.SURFACE, data = Bundle().apply { putParcelable(P.KEY_SURFACE, newHost.surfacePackage) })
        } catch (e: RuntimeException) {
            Log.e(TAG, "attach", e)
            releaseHost()
            send(P.STATE, arg1 = P.STATE_FAILED, data = messageBundle("The document could not be shown (${e.message ?: e.javaClass.simpleName})"))
        }
    }

    /** The app's surface went away. The host is kept so the same window can show it again. */
    private fun detach() = Unit

    private fun releaseHost() {
        host?.release()
        host = null
        hostToken = null
        hostDisplay = -1
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

    private fun exportPdf(requestId: Int, target: File) {
        val doc = document ?: return
        scope.launch {
            val error = runCatching { doc.exportPdf(target) }.onFailure { Log.w(TAG, "export", it) }.exceptionOrNull()
            send(P.EXPORTED, arg1 = requestId, data = error?.let { messageBundle(it.message ?: it.javaClass.simpleName) })
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
