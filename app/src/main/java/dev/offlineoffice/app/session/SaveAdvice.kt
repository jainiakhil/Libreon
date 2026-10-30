// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.session

/**
 * Optional hints a session gives the save flow. A signed PDF, for example, should be saved
 * as a new file by default so the original stays untouched.
 */
interface SaveAdvice {
    /** True when "Save" should offer "Save as new file" first instead of overwriting. */
    val prefersSaveAsNew: Boolean

    /** File name suggested for "Save as new file", derived from the current [displayName]. */
    fun suggestedSaveName(displayName: String): String
}
