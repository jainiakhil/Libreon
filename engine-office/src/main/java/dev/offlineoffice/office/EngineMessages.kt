// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

/** The few protocol messages the app reacts to; everything else is only for the front-end. */
internal sealed interface EngineEvent {
    data class Modified(val modified: Boolean) : EngineEvent
    data class SaveResult(val success: Boolean) : EngineEvent
    /** The document is open and showing. */
    data object Loaded : EngineEvent
    data class LoadFailed(val kind: String) : EngineEvent
}

internal object EngineMessages {
    /** Only the start of a message is decoded: tile and image messages can be megabytes. */
    private const val PEEK = 512

    fun parse(message: ByteArray): EngineEvent? {
        val head = String(message, 0, minOf(message.size, PEEK), Charsets.UTF_8)
        return when {
            head.startsWith("statechanged:") && ".uno:ModifiedStatus" in head ->
                // "statechanged: .uno:ModifiedStatus=true", or the JSON form with "state":"true"
                EngineEvent.Modified(Regex("""ModifiedStatus\W*(?:state\W*)?(true|false)""").find(head)?.groupValues?.get(1) == "true")
            head.startsWith("unocommandresult:") && "\".uno:Save\"" in head ->
                // An unmodified document needs no save; its file is already current.
                EngineEvent.SaveResult("\"success\":true" in head || "\"unmodified\"" in head)
            head.startsWith("error:") && "cmd=save" in head -> EngineEvent.SaveResult(false)
            // A password prompt is handled by the front-end itself.
            head.startsWith("error:") && "cmd=load" in head && "password" !in head ->
                EngineEvent.LoadFailed(Regex("""kind=(\S+)""").find(head)?.groupValues?.get(1) ?: "unknown")
            head.startsWith("status:") -> EngineEvent.Loaded
            else -> null
        }
    }
}
