// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WordCounterTest {
    private fun words(s: String) = WordCounter.count(s)

    @Test
    fun emptyAndBlank() {
        assertEquals(0, words(""))
        assertEquals(0, words("   \n\t  "))
        assertEquals(0, words("... -- !!"))
    }

    @Test
    fun plainWordsAndPunctuation() {
        assertEquals(4, words("The quick brown fox."))
        assertEquals(3, words("one,two;three"))
        assertEquals(2, words("  leading and  "))
    }

    @Test
    fun contractionsAndHyphens() {
        assertEquals(3, words("I don't know"))
        assertEquals(2, words("It’s well-known."))
        assertEquals(3, words("-dash at- start"))
        assertEquals(2, words("a - b"))
    }

    @Test
    fun numbers() {
        assertEquals(1, words("3.14"))
        assertEquals(1, words("1,000,000"))
        assertEquals(2, words("end. Next"))
        assertEquals(3, words("cost 4.50 each"))
    }

    @Test
    fun lineBreaksSeparateWords() {
        assertEquals(4, words("one\ntwo\r\nthree\rfour"))
    }

    @Test
    fun cjkCountsEachCharacter() {
        assertEquals(4, words("日本語だ"))
        assertEquals(3, words("hello 世界"))
    }

    @Test
    fun accentsAndOtherScripts() {
        assertEquals(2, words("café au"))
        assertEquals(2, words("नमस्ते दुनिया"))
        assertEquals(2, words("Привет мир"))
    }

    @Test
    fun emojiAndSurrogatesAreNotWords() {
        assertEquals(2, words("hi 😀 there"))
        assertEquals(1, words("𝐀𝐁𝐂"))
    }

    @Test
    fun characterCount() {
        assertEquals(0, WordCounter.countChars(""))
        assertEquals(6, WordCounter.countChars("a b\r\nc d"))
        assertEquals(3, WordCounter.countChars("😀a😀"))
    }
}
