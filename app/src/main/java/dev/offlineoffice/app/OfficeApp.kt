// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import dev.offlineoffice.app.data.AppDatabase
import dev.offlineoffice.app.doc.FileAccess
import dev.offlineoffice.app.session.SessionBudget
import dev.offlineoffice.app.tabs.TabManager
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OfficeApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var tabs: TabManager
        private set

    /** Completes once the persisted tab bar has been restored. */
    val ready = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.create(this)
        val heapMb = getSystemService(ActivityManager::class.java).largeMemoryClass
        tabs = TabManager(
            context = this,
            db = db,
            files = FileAccess(this),
            recoveryRoot = File(filesDir, "recovery"),
            budget = SessionBudget.forHeap(heapMb),
            scope = scope,
        )
        scope.launch {
            tabs.restore()
            ready.complete(Unit)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) tabs.trimMemory()
    }
}
