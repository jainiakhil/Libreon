// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import org.json.JSONObject

/** The few protocol messages the app reacts to; everything else is only for the front-end. */
internal sealed interface EngineEvent {
    data class Modified(val modified: Boolean) : EngineEvent
    data class SaveResult(val success: Boolean) : EngineEvent
    /** The document is open and showing. */
    data object Loaded : EngineEvent
    data class LoadFailed(val kind: String) : EngineEvent
    /** A find-all search matched [count] times. */
    data class SearchResults(val count: Int) : EngineEvent
    data object SearchNotFound : EngineEvent
    /** Writer's status-bar word count, e.g. "Selected: 3 of 1,204 words, 17 of 6,880 characters". */
    data class WordCount(val text: String) : EngineEvent
    /** Reply to a text-selection request: the selection as plain text. */
    data class TextSelection(val text: String) : EngineEvent
}

internal object EngineMessages {
    /** Only the start of a message is decoded: tile and image messages can be megabytes. */
    private const val PEEK = 512

    private const val SELECTION = "textselectioncontent:"
    private const val SEARCH_RESULTS = "searchresultselection:"

    fun parse(message: ByteArray): EngineEvent? {
        val head = String(message, 0, minOf(message.size, PEEK), Charsets.UTF_8)
        return when {
            head.startsWith("statechanged:") && ".uno:ModifiedStatus" in head ->
                // "statechanged: .uno:ModifiedStatus=true", or the JSON form with "state":"true"
                EngineEvent.Modified(Regex("""ModifiedStatus\W*(?:state\W*)?(true|false)""").find(head)?.groupValues?.get(1) == "true")
            head.startsWith("statechanged:") && ".uno:StateWordCount" in head -> wordCount(head)
            head.startsWith("unocommandresult:") && "\".uno:Save\"" in head ->
                // An unmodified document needs no save; its file is already current.
                EngineEvent.SaveResult("\"success\":true" in head || "\"unmodified\"" in head)
            head.startsWith("error:") && "cmd=save" in head -> EngineEvent.SaveResult(false)
            // A password prompt is handled by the front-end itself.
            head.startsWith("error:") && "cmd=load" in head && "password" !in head ->
                EngineEvent.LoadFailed(Regex("""kind=(\S+)""").find(head)?.groupValues?.get(1) ?: "unknown")
            head.startsWith("status:") -> EngineEvent.Loaded
            head.startsWith(SEARCH_RESULTS) -> searchResults(String(message, Charsets.UTF_8))
            head.startsWith("searchnotfound:") -> EngineEvent.SearchNotFound
            head.startsWith(SELECTION) ->
                EngineEvent.TextSelection(String(message, Charsets.UTF_8).removePrefix(SELECTION).removePrefix(" "))
            else -> null
        }
    }

    // "statechanged: .uno:StateWordCount=12 words, 60 characters", or the JSON form.
    private fun wordCount(head: String): EngineEvent? {
        val body = head.removePrefix("statechanged:").trim()
        val text = if (body.startsWith("{")) {
            runCatching { JSONObject(body).optString("state") }.getOrNull()
        } else {
            body.substringAfter('=', "")
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }?.let(EngineEvent::WordCount)
    }

    private fun searchResults(message: String): EngineEvent {
        val json = runCatching { JSONObject(message.removePrefix(SEARCH_RESULTS)) }.getOrNull()
        val count = json?.optJSONArray("searchResultSelection")?.length() ?: 0
        return if (count > 0) EngineEvent.SearchResults(count) else EngineEvent.SearchNotFound
    }
}
