// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

/** Messages between [OfficeClient] (app process) and [OfficeService] (`:office` process). */
internal object OfficeProtocol {
    // Client to service
    /** Data: [KEY_PATH], [KEY_DARK]. `replyTo` receives every service message. */
    const val OPEN = 1
    /** Data: [KEY_HOST_TOKEN], [KEY_DISPLAY], [KEY_KEYBOARD]; arg1/arg2: width/height. */
    const val ATTACH = 2
    /** arg1/arg2: width/height. */
    const val RESIZE = 3
    const val DETACH = 4
    /** arg1: request id. */
    const val SAVE = 5
    /** arg1: 1 for the dark theme. */
    const val THEME = 6

    // Service to client
    /** arg1: the service's pid. */
    const val OPENED = 101
    /** Data: [KEY_SURFACE]. */
    const val SURFACE = 102
    /** arg1: [STATE_LOADING], [STATE_READY] or [STATE_FAILED]; data: [KEY_MESSAGE] when failed. */
    const val STATE = 103
    /** arg1: 1 when modified. */
    const val MODIFIED = 104
    const val SAVE_REQUESTED = 105
    /** arg1: request id; data: [KEY_MESSAGE] when the save failed. */
    const val SAVED = 106

    const val STATE_LOADING = 0
    const val STATE_READY = 1
    const val STATE_FAILED = 2

    const val KEY_PATH = "path"
    const val KEY_DARK = "dark"
    const val KEY_HOST_TOKEN = "hostToken"
    const val KEY_DISPLAY = "display"
    const val KEY_KEYBOARD = "keyboard"
    const val KEY_SURFACE = "surface"
    const val KEY_MESSAGE = "message"
}
