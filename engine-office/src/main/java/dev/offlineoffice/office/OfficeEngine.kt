// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The process-wide Office engine. It starts once and then holds one document at a time;
 * every call into it runs on its own thread, in order.
 */
internal object OfficeEngine {
    private const val TAG = "OfficeEngine"
    private const val ASSETS_STAMP = "lok-assets.stamp"
    private const val CALL_TIMEOUT_MS = 60_000L
    private const val STUCK_MESSAGE = "The Office engine stopped responding. Restart the app to open Office documents again."

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "office-engine") }
    private val prepareLock = Mutex()
    private var prepared = false

    /** Set once a call never returned; the engine cannot be used again in this process. */
    @Volatile
    private var stuck = false

    /** Loads the native code and unpacks the engine's runtime files after each app update. */
    suspend fun prepare(context: Context) = prepareLock.withLock {
        if (prepared) return@withLock
        call {
            val app = context.applicationContext
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val stamp = "${info.longVersionCode}:${info.lastUpdateTime}"
            val stampFile = File(app.applicationInfo.dataDir, ASSETS_STAMP)
            if (!stampFile.isFile || stampFile.readText() != stamp) {
                val started = System.currentTimeMillis()
                copyAssets(app.assets, "unpack", File(app.applicationInfo.dataDir))
                stampFile.writeText(stamp)
                Log.i(TAG, "unpacked engine files in ${System.currentTimeMillis() - started} ms")
            }
            System.loadLibrary("androidapp")
        }
        prepared = true
    }

    /** Runs [block] on the engine thread, giving up (for good) if it never returns. */
    suspend fun <T> call(block: () -> T): T {
        if (stuck) throw IOException(STUCK_MESSAGE)
        // Not withContext: that would keep waiting for a blocked call after the timeout.
        val result = CompletableDeferred<T>()
        thread.execute { runCatching(block).fold(result::complete, result::completeExceptionally) }
        return try {
            withTimeout(CALL_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            stuck = true
            throw IOException(STUCK_MESSAGE, e)
        }
    }

    private fun copyAssets(assets: AssetManager, from: String, to: File) {
        val children = assets.list(from).orEmpty()
        if (children.isNotEmpty()) {
            for (child in children) copyAssets(assets, "$from/$child", File(to, child))
            return
        }
        // A file, or an empty directory (which cannot be opened).
        val input = try {
            assets.open(from)
        } catch (e: FileNotFoundException) {
            to.mkdirs()
            return
        }
        to.parentFile?.mkdirs()
        input.use { source -> to.outputStream().use { source.copyTo(it) } }
    }
}
