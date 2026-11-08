// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.tabs

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.offlineoffice.app.data.AppDatabase
import dev.offlineoffice.app.data.GrantType
import dev.offlineoffice.app.data.RecentEntity
import dev.offlineoffice.app.data.TabEntity
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.doc.ExternalChangeException
import dev.offlineoffice.app.doc.FileAccess
import dev.offlineoffice.app.session.DocumentSession
import dev.offlineoffice.app.session.OfficeSession
import dev.offlineoffice.app.session.PdfExport
import dev.offlineoffice.app.session.PdfSession
import dev.offlineoffice.app.session.SessionBudget
import dev.offlineoffice.app.session.TextSession
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a document came from, which decides how long we can use its URI. */
enum class OpenOrigin { PICKER, INTENT }

sealed interface SessionState {
    data object Loading : SessionState
    data class Ready(val session: DocumentSession) : SessionState
    data class Failed(val message: String) : SessionState
}

sealed interface SaveResult {
    data object Saved : SaveResult
    /** The original URI is no longer writable (grant lost or read-only): ask for a location. */
    data object NeedsSaveAs : SaveResult
    /** The file changed on disk since it was opened. */
    data object Conflict : SaveResult
    data class Failed(val message: String) : SaveResult
}

/**
 * Owns the tab list and the engine sessions behind it.
 *
 * Tabs are persisted in Room on every change, so the tab bar survives process death. Only a
 * budgeted number of sessions per engine stay "hot"; the least recently used are parked
 * (view state captured, recovery copy written if dirty, engine closed) and reloaded on demand.
 * All public methods must be called from the main thread.
 */
class TabManager(
    private val context: Context,
    private val db: AppDatabase,
    private val files: FileAccess,
    private val recoveryRoot: File,
    private val budget: SessionBudget,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Whether a tab's Office page should open dark (the app theme, unless the tab asks for light pages). */
    var darkPagesFor: (tabId: String) -> Boolean = { false }
    private val _tabs = MutableStateFlow<List<TabEntity>>(emptyList())
    val tabs: StateFlow<List<TabEntity>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /** Tabs restored with unsaved changes from a recovery copy, for the "restored" banner. */
    private val _restored = MutableStateFlow<Set<String>>(emptySet())
    val restored: StateFlow<Set<String>> = _restored.asStateFlow()

    val closedTabs: Flow<List<TabEntity>> = db.tabs().closedTabs()
    val recents: Flow<List<RecentEntity>> = db.recents().all()

    private val dirtyWatchers = mutableMapOf<String, Job>()
    private val recoveryJobs = mutableMapOf<String, Job>()

    /** Reloads the tab bar after a cold start. Transient grants never survive process death. */
    suspend fun restore() {
        val active = mutex.withLock {
            val stored = db.tabs().openTabs().map { tab ->
                if (tab.grant == GrantType.TRANSIENT) tab.copy(grant = GrantType.PRIVATE_COPY) else tab
            }
            db.tabs().upsertAll(stored)
            _tabs.value = stored
            _restored.value = stored.filter { it.dirty && it.recoveryPath != null }.map { it.id }.toSet()
            stored.maxByOrNull { it.lastActiveAt }?.id
        }
        // Only the visible tab is loaded; the rest stay parked until selected.
        active?.let(::activate)
    }

    suspend fun open(uri: Uri, origin: OpenOrigin, mimeHint: String? = null): String? {
        val tabId = mutex.withLock {
            _tabs.value.firstOrNull { it.uri == uri.toString() }?.let { return@withLock it.id }

            val meta = files.meta(uri)
            val kind = DocumentKind.detect(meta.displayName, meta.mimeType ?: mimeHint)
                ?: throw IllegalArgumentException("Unsupported file: ${meta.displayName}")
            val id = UUID.randomUUID().toString()
            val persisted = files.tryPersist(uri, writable = true) || files.hasPersistedGrant(uri)
            val grant = when {
                persisted -> GrantType.PERSISTED
                origin == OpenOrigin.PICKER -> GrantType.PERSISTED // picker grants persist; tryPersist covers read-only providers
                else -> GrantType.TRANSIENT
            }
            val working = files.copyToPrivate(uri, id, meta.displayName)
            val now = System.currentTimeMillis()
            val tab = TabEntity(
                id = id, uri = uri.toString(), displayName = meta.displayName, kind = kind,
                mimeType = meta.mimeType ?: mimeHint, grant = grant, workingCopyPath = working.path,
                position = (_tabs.value.maxOfOrNull { it.position } ?: -1) + 1,
                openedAt = now, lastActiveAt = now,
                sourceSize = meta.size, sourceModified = meta.lastModified,
            )
            db.tabs().upsert(tab)
            _tabs.update { it + tab }
            db.recents().upsert(
                RecentEntity(
                    uri = tab.uri, displayName = tab.displayName, kind = kind, mimeType = tab.mimeType,
                    size = meta.size, lastOpenedAt = now, reopenable = grant == GrantType.PERSISTED,
                ),
            )
            id
        }
        activate(tabId)
        return tabId
    }

    fun activate(id: String) {
        scope.launch {
            mutex.withLock {
                val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock
                _activeId.value = id
                updateTab(tab.copy(lastActiveAt = System.currentTimeMillis()))
                if (_sessions.value[id] !is SessionState.Ready) {
                    makeRoomFor(tab)
                    load(tab)
                }
                enforceBudget(keep = id)
            }
        }
    }

    /** Closes a tab. The UI must confirm first if the tab is dirty. */
    fun close(id: String) {
        scope.launch {
            mutex.withLock {
                val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock
                unload(id)
                files.deleteWorkingDir(id)
                File(recoveryRoot, id).deleteRecursively()
                val closed = tab.copy(
                    closedAt = System.currentTimeMillis(), dirty = false, recoveryPath = null,
                    workingCopyPath = null, viewState = tab.viewState,
                )
                db.tabs().upsert(closed)
                db.tabs().trimClosed()
                _tabs.update { list -> list.filterNot { it.id == id } }
                _restored.update { it - id }
                if (_activeId.value == id) _activeId.value = _tabs.value.maxByOrNull { it.lastActiveAt }?.id
            }
        }
    }

    /** Closes every tab. The UI must confirm first if any is dirty. */
    fun closeAll() = _tabs.value.map { it.id }.forEach(::close)

    /** Closes every tab without keeping it, and forgets recent and recently closed files. */
    fun clearHome() {
        scope.launch {
            mutex.withLock {
                for (tab in _tabs.value) {
                    unload(tab.id)
                    files.deleteWorkingDir(tab.id)
                    File(recoveryRoot, tab.id).deleteRecursively()
                    db.tabs().delete(tab.id)
                }
                db.tabs().deleteClosed()
                db.recents().clear()
                _tabs.value = emptyList()
                _restored.value = emptySet()
                _activeId.value = null
            }
        }
    }

    /** Reopens a closed tab; only possible while we still hold a persisted grant. */
    suspend fun reopen(closed: TabEntity): Boolean {
        val uri = Uri.parse(closed.uri)
        if (!files.hasPersistedGrant(uri)) return false
        db.tabs().delete(closed.id)
        open(uri, OpenOrigin.PICKER, closed.mimeType)?.let { newId ->
            closed.viewState?.let { vs -> mutex.withLock { _tabs.value.firstOrNull { it.id == newId }?.let { updateTab(it.copy(viewState = vs)) } } }
        }
        return true
    }

    fun move(id: String, toIndex: Int) {
        scope.launch {
            mutex.withLock {
                val list = _tabs.value.toMutableList()
                val from = list.indexOfFirst { it.id == id }
                if (from < 0) return@withLock
                list.add(toIndex.coerceIn(0, list.lastIndex), list.removeAt(from))
                val reordered = list.mapIndexed { i, t -> t.copy(position = i) }
                db.tabs().upsertAll(reordered)
                _tabs.value = reordered
            }
        }
    }

    suspend fun save(id: String): SaveResult = mutex.withLock {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock SaveResult.Failed("No such tab")
        if (tab.grant == GrantType.PRIVATE_COPY) return@withLock SaveResult.NeedsSaveAs
        writeBack(tab, Uri.parse(tab.uri), checkExternal = true)
    }

    /** Saves to a new location chosen with ACTION_CREATE_DOCUMENT; the tab then points there. */
    suspend fun saveAs(id: String, target: Uri): SaveResult = mutex.withLock {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock SaveResult.Failed("No such tab")
        files.tryPersist(target, writable = true)
        val result = writeBack(tab, target, checkExternal = false)
        if (result == SaveResult.Saved) {
            val meta = files.meta(target)
            updateTab(
                currentTab(id).copy(
                    uri = target.toString(), displayName = meta.displayName, grant = GrantType.PERSISTED,
                ),
            )
        }
        result
    }

    /**
     * Writes the open document, with any unsaved edits, to [target] (as a PDF with [asPdf])
     * without saving it: for sharing and exporting.
     */
    suspend fun exportCopy(id: String, target: File, asPdf: Boolean = false) = mutex.withLock {
        val session = (_sessions.value[id] as? SessionState.Ready)?.session ?: throw IOException("The document is not open")
        if (asPdf) {
            (session as? PdfExport ?: throw IOException("This document cannot be exported as PDF")).exportPdf(target)
        } else {
            session.writeTo(target)
        }
    }

    /** Saves over the original even though it changed on disk (after the user confirmed). */
    suspend fun overwrite(id: String): SaveResult = mutex.withLock {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock SaveResult.Failed("No such tab")
        writeBack(tab, Uri.parse(tab.uri), checkExternal = false)
    }

    /** Throws away unsaved changes and reloads the document from its last saved state. */
    fun discardChanges(id: String) {
        scope.launch {
            mutex.withLock {
                val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock
                unload(id)
                File(recoveryRoot, id).deleteRecursively()
                val clean = tab.copy(dirty = false, recoveryPath = null, recoveryUpdatedAt = null)
                updateTab(clean)
                _restored.update { it - id }
                if (_activeId.value == id) load(clean)
            }
        }
    }

    fun dismissRestored(id: String) = _restored.update { it - id }

    /**
     * Reopens a tab whose engine stopped, from its recovery copy if it has unsaved changes.
     * Edits made after the last recovery copy are lost with the engine.
     */
    fun reload(id: String) {
        scope.launch {
            mutex.withLock {
                if (_tabs.value.none { it.id == id }) return@withLock
                unload(id)
                load(currentTab(id))
            }
        }
    }

    /** Called from onStop: capture view state and recovery copies for every hot session. */
    fun persistAll() {
        scope.launch {
            mutex.withLock {
                for ((id, state) in _sessions.value) {
                    val session = (state as? SessionState.Ready)?.session ?: continue
                    checkpoint(currentTab(id), session)
                }
            }
        }
    }

    /** Called on memory pressure: park everything except the visible tab. */
    fun trimMemory() {
        scope.launch {
            mutex.withLock {
                _sessions.value.keys.filter { it != _activeId.value }.forEach { park(it) }
            }
        }
    }

    // --- internals (call with mutex held) ---

    private fun currentTab(id: String): TabEntity = _tabs.value.first { it.id == id }

    private suspend fun updateTab(tab: TabEntity) {
        _tabs.update { list -> list.map { if (it.id == tab.id) tab else it } }
        db.tabs().upsert(tab)
    }

    private suspend fun load(tab: TabEntity) {
        _sessions.update { it + (tab.id to SessionState.Loading) }
        val session = createSession(tab)
        try {
            val file = sourceFileFor(tab)
            session.load(file, tab.viewState)
            _sessions.update { it + (tab.id to SessionState.Ready(session)) }
            watchDirty(tab.id, session)
        } catch (e: Exception) {
            Log.e(TAG, "load ${tab.displayName}", e)
            runCatching { session.close() }
            _sessions.update { it + (tab.id to SessionState.Failed(e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Recovery copy if there are unsaved edits, otherwise a fresh copy of the source if reachable. */
    private suspend fun sourceFileFor(tab: TabEntity): File {
        tab.recoveryPath?.let { File(it) }?.takeIf { tab.dirty && it.exists() }?.let { return it }
        if (tab.grant != GrantType.PRIVATE_COPY) {
            runCatching {
                val fresh = files.copyToPrivate(Uri.parse(tab.uri), tab.id, tab.displayName)
                val meta = files.meta(Uri.parse(tab.uri))
                updateTab(currentTab(tab.id).copy(workingCopyPath = fresh.path, sourceSize = meta.size, sourceModified = meta.lastModified))
                return fresh
            }.onFailure { Log.w(TAG, "source unreachable, using private copy: ${tab.displayName}", it) }
        }
        return File(checkNotNull(tab.workingCopyPath) { "No copy of ${tab.displayName} is available" })
    }

    private fun createSession(tab: TabEntity): DocumentSession = when (tab.kind.engine) {
        DocumentKind.Engine.PDF -> PdfSession(tab.id)
        DocumentKind.Engine.TEXT -> TextSession(tab.id, tab.kind)
        DocumentKind.Engine.OFFICE -> OfficeSession(tab.id, tab.kind, context, File(context.noBackupFilesDir, "office/${tab.id}")) { darkPagesFor(tab.id) }
    }

    private fun watchDirty(id: String, session: DocumentSession) {
        dirtyWatchers.remove(id)?.cancel()
        dirtyWatchers[id] = scope.launch {
            session.dirty.collect { dirty ->
                if (!dirty) return@collect
                mutex.withLock {
                    val tab = _tabs.value.firstOrNull { it.id == id } ?: return@withLock
                    if (!tab.dirty) updateTab(tab.copy(dirty = true))
                }
                scheduleRecovery(id, session)
            }
        }
    }

    /** Debounced recovery copy while the user edits. */
    private fun scheduleRecovery(id: String, session: DocumentSession) {
        recoveryJobs.remove(id)?.cancel()
        recoveryJobs[id] = scope.launch {
            delay(RECOVERY_DEBOUNCE_MS)
            mutex.withLock { _tabs.value.firstOrNull { it.id == id }?.let { checkpoint(it, session) } }
        }
    }

    private suspend fun checkpoint(tab: TabEntity, session: DocumentSession) {
        var updated = tab.copy(viewState = session.captureViewState() ?: tab.viewState)
        if (tab.dirty || session.dirty.value) {
            runCatching {
                val dir = File(recoveryRoot, tab.id).apply { mkdirs() }
                val target = File(dir, "recovery-" + (tab.workingCopyPath?.let { File(it).name } ?: "document"))
                val temp = File(dir, target.name + ".tmp")
                session.writeTo(temp)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                updated = updated.copy(dirty = true, recoveryPath = target.path, recoveryUpdatedAt = System.currentTimeMillis())
            }.onFailure { Log.e(TAG, "recovery copy failed for ${tab.displayName}", it) }
        }
        updateTab(updated)
    }

    private suspend fun park(id: String) {
        val session = (_sessions.value[id] as? SessionState.Ready)?.session
        if (session != null) checkpoint(currentTab(id), session)
        unload(id)
    }

    private suspend fun unload(id: String) {
        dirtyWatchers.remove(id)?.cancel()
        recoveryJobs.remove(id)?.cancel()
        (_sessions.value[id] as? SessionState.Ready)?.session?.let { runCatching { it.close() } }
        _sessions.update { it - id }
    }

    /**
     * Parks sessions of [tab]'s engine so that loading it stays within budget. This must happen
     * before the load: the Office engine drops its previous document as soon as it opens the
     * next, so that document has to be checkpointed and closed first.
     */
    private suspend fun makeRoomFor(tab: TabEntity) {
        _sessions.value.keys
            .mapNotNull { id -> _tabs.value.firstOrNull { it.id == id } }
            .filter { it.id != tab.id && it.kind.engine == tab.kind.engine }
            .sortedByDescending { it.lastActiveAt }
            .drop(budget.limitFor(tab.kind.engine) - 1)
            .forEach { park(it.id) }
    }

    private suspend fun enforceBudget(keep: String) {
        for (engine in DocumentKind.Engine.entries) {
            val hot = _sessions.value.keys
                .mapNotNull { id -> _tabs.value.firstOrNull { it.id == id } }
                .filter { it.kind.engine == engine }
                .sortedByDescending { if (it.id == keep) Long.MAX_VALUE else it.lastActiveAt }
            hot.drop(budget.limitFor(engine)).forEach { park(it.id) }
        }
    }

    private suspend fun writeBack(tab: TabEntity, target: Uri, checkExternal: Boolean): SaveResult {
        val session = (_sessions.value[tab.id] as? SessionState.Ready)?.session
            ?: return SaveResult.Failed("Document is not loaded")
        val dir = File(recoveryRoot, tab.id).apply { mkdirs() }
        val temp = File(dir, "save.tmp")
        return try {
            session.writeTo(temp)
            val written = files.safeWrite(
                target, temp,
                expectedSize = tab.sourceSize.takeIf { checkExternal },
                expectedModified = tab.sourceModified.takeIf { checkExternal },
            )
            // The provider's size column may still be stale; remember what we actually wrote.
            val meta = files.meta(target)
            tab.workingCopyPath?.let { temp.copyTo(File(it), overwrite = true) }
            session.markSaved()
            File(recoveryRoot, tab.id).deleteRecursively()
            updateTab(currentTab(tab.id).copy(dirty = false, recoveryPath = null, recoveryUpdatedAt = null, sourceSize = written, sourceModified = meta.lastModified))
            _restored.update { it - tab.id }
            SaveResult.Saved
        } catch (e: ExternalChangeException) {
            SaveResult.Conflict
        } catch (e: SecurityException) {
            SaveResult.NeedsSaveAs
        } catch (e: Exception) {
            Log.e(TAG, "save ${tab.displayName}", e)
            SaveResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val TAG = "TabManager"
        const val RECOVERY_DEBOUNCE_MS = 2_000L
    }
}
