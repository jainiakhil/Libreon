// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

import dev.offlineoffice.app.doc.DocumentKind
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * A live, engine-backed document. Sessions are "hot" while they hold engine memory; the
 * TabManager parks the least recently used ones (capture view state, write a recovery copy
 * if dirty, then [close]) and recreates them on demand.
 */
interface DocumentSession {
    val tabId: String
    val kind: DocumentKind

    /** True when there are edits not yet saved to the original document. */
    val dirty: StateFlow<Boolean>

    /** Loads [file] (a private copy, recovery copy or a temp copy of the source). */
    suspend fun load(file: File, viewState: String?)

    /** Engine-specific JSON describing where the user is (page, scroll, cursor…). */
    fun captureViewState(): String?

    /**
     * Writes the current document, in its original format, to [target].
     * Used both for recovery copies and as the first step of Save.
     */
    suspend fun writeTo(target: File)

    /** Marks the current content as saved. */
    fun markSaved()

    /** Releases engine resources. The session must not be used afterwards. */
    suspend fun close()
}

/** How many sessions of each engine may be hot at once. Office is limited by the engine itself. */
data class SessionBudget(val office: Int, val pdf: Int, val text: Int) {
    fun limitFor(engine: DocumentKind.Engine): Int = when (engine) {
        DocumentKind.Engine.OFFICE -> office
        DocumentKind.Engine.PDF -> pdf
        DocumentKind.Engine.TEXT -> text
    }

    companion object {
        /** Scales with the per-app heap (largeHeap is on). */
        fun forHeap(largeMemoryClassMb: Int): SessionBudget = when {
            largeMemoryClassMb >= 512 -> SessionBudget(office = 1, pdf = 3, text = 8)
            largeMemoryClassMb >= 256 -> SessionBudget(office = 1, pdf = 2, text = 6)
            else -> SessionBudget(office = 1, pdf = 1, text = 4)
        }
    }
}
