// SPDX-License-Identifier: GPL-3.0-or-later
package org.libreoffice.androidlib

import android.content.res.AssetManager
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JNI bridge to the engine's Android glue (`libandroidapp.so`).
 *
 * The glue binds its entry points and callbacks to this class name, so the name stays even
 * though this is not an Activity: it is a plain object, one per open Office document, owned by
 * [dev.offlineoffice.office.OfficeDocument]. It is also the front-end's `COOLMessageHandler`.
 */
class LOActivity internal constructor(private val listener: Listener) {

    internal interface Listener {
        /** A message from the engine for the front-end's fake websocket. Any thread. */
        fun onEngineMessage(message: ByteArray)

        /** A message from the front-end. Returns true to pass it on to the engine. */
        fun onFrontEndMessage(message: String): Boolean
    }

    @Volatile
    internal var hardwareKeyboard = false

    /** Front-end to engine; also called by the glue for messages it posts directly. */
    @JavascriptInterface
    fun postMobileMessage(message: String) {
        if (listener.onFrontEndMessage(message)) postMobileMessageNative(message)
    }

    @JavascriptInterface
    fun postMobileError(message: String) {
        Log.w(TAG, "front-end error: $message")
    }

    @JavascriptInterface
    fun postMobileDebug(message: String) {
        Log.d(TAG, "front-end: $message")
    }

    @JavascriptInterface
    fun isChromeOS(): Boolean = false

    @JavascriptInterface
    fun hasHardwareKeyboard(): Boolean = hardwareKeyboard

    /** Engine to front-end. Called by the glue from its forwarding thread. */
    fun rawCallFakeWebsocketOnMessage(message: ByteArray) = listener.onEngineMessage(message)

    /**
     * Starts the engine on first use and points it at [loadFileURL]. On later calls it closes
     * the previous document first. Blocks; call on the engine thread.
     */
    external fun createCOOLWSD(
        dataDir: String, cacheDir: String, apkFile: String, assetManager: AssetManager,
        loadFileURL: String, uiMode: String, userName: String,
    )

    /** Sends a protocol message as if from the front-end. "BYE" blocks until the document has closed. */
    external fun postMobileMessageNative(message: String)

    external fun saveAs(fileUri: String, format: String, options: String?)

    external fun postUnoCommand(command: String, arguments: String?, notifyWhenFinished: Boolean)

    private companion object {
        const val TAG = "OfficeBridge"
    }
}
