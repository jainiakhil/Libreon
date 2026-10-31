// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineMessagesTest {
    @Test
    fun parseStateChangedModifiedStatus() {
        assertEquals(
            EngineEvent.Modified(true),
            EngineMessages.parse("statechanged: .uno:ModifiedStatus=true".toByteArray())
        )
        assertEquals(
            EngineEvent.Modified(false),
            EngineMessages.parse("statechanged: .uno:ModifiedStatus=false".toByteArray())
        )
    }

    @Test
    fun parseStateChangedModifiedStatusJson() {
        val json = """statechanged: {"commandName":".uno:ModifiedStatus","state":"true"}"""
        assertEquals(
            EngineEvent.Modified(true),
            EngineMessages.parse(json.toByteArray())
        )
    }

    @Test
    fun parseUnoCommandResultSaveSuccessTrue() {
        val msg = """unocommandresult: {"commandName":".uno:Save","success":true}"""
        assertEquals(
            EngineEvent.SaveResult(true),
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseUnoCommandResultSaveSuccessFalse() {
        val msg = """unocommandresult: {"commandName":".uno:Save","success":false}"""
        assertEquals(
            EngineEvent.SaveResult(false),
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseUnoCommandResultSaveUnmodifiedCountsAsSuccess() {
        val msg = """unocommandresult: {"commandName":".uno:Save","success":false,"result":{"value":"unmodified"}}"""
        assertEquals(
            EngineEvent.SaveResult(true),
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseUnoCommandResultOtherCommandReturnsNull() {
        val msg = """unocommandresult: {"commandName":".uno:Bold","success":true}"""
        assertNull(EngineMessages.parse(msg.toByteArray()))
    }

    @Test
    fun parseErrorCmdSaveReturnsSaveResultFalse() {
        val msg = "error: cmd=save kind=savefailed"
        assertEquals(
            EngineEvent.SaveResult(false),
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseErrorCmdLoadFailedDocLoading() {
        val msg = "error: cmd=load kind=faileddocloading"
        assertEquals(
            EngineEvent.LoadFailed("faileddocloading"),
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseErrorCmdLoadPasswordRequiredReturnsNull() {
        val msg = "error: cmd=load kind=passwordrequired:to-view"
        assertNull(EngineMessages.parse(msg.toByteArray()))
    }

    @Test
    fun parseStatusReturnsLoaded() {
        val msg = "status: type=text parts=1"
        assertEquals(
            EngineEvent.Loaded,
            EngineMessages.parse(msg.toByteArray())
        )
    }

    @Test
    fun parseLargeTileBinaryMessageReturnsNull() {
        val prefix = "tile: part=0 width=256 height=256\n".toByteArray()
        val message = ByteArray(2 * 1024 * 1024)
        prefix.copyInto(message)
        assertNull(EngineMessages.parse(message))
    }

    @Test
    fun parseUnrelatedMessagesReturnNull() {
        assertNull(EngineMessages.parse("pong".toByteArray()))
        assertNull(EngineMessages.parse("foobar: unknown".toByteArray()))
    }

    @Test
    fun parseWordCountPlainAndJson() {
        assertEquals(
            EngineEvent.WordCount("12 words, 60 characters"),
            EngineMessages.parse("statechanged: .uno:StateWordCount=12 words, 60 characters".toByteArray()),
        )
        assertEquals(
            EngineEvent.WordCount("Selected: 2 of 12 words, 9 of 60 characters"),
            EngineMessages.parse("""statechanged: {"commandName":".uno:StateWordCount","state":"Selected: 2 of 12 words, 9 of 60 characters"}""".toByteArray()),
        )
        assertNull(EngineMessages.parse("statechanged: .uno:StateWordCount=".toByteArray()))
    }

    @Test
    fun parseSearchResultsCountsMatches() {
        val msg = """searchresultselection: {"searchString":"a","highlightAll":"true","searchResultSelection":[{"part":"0","rectangles":"1, 2, 3, 4"},{"part":"0","rectangles":"5, 6, 7, 8"}]}"""
        assertEquals(EngineEvent.SearchResults(2), EngineMessages.parse(msg.toByteArray()))
        assertEquals(EngineEvent.SearchNotFound, EngineMessages.parse("searchnotfound: find".toByteArray()))
    }

    @Test
    fun parseTextSelectionKeepsWholeMessage() {
        val text = "word ".repeat(400)
        assertEquals(EngineEvent.TextSelection(text), EngineMessages.parse("textselectioncontent: $text".toByteArray()))
    }
}
