// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeSocketTest {
    @Test
    fun frameTextMessageExactBytes() {
        val message = "hello".toByteArray()
        val framed = FakeSocket.frame(1, message)
        val expected = "T0x1\n0x5\nhello\n".toByteArray()
        assertArrayEquals(expected, framed)
    }

    @Test
    fun frameMessageContainingNewlineExactBytes() {
        val message = "hello\nworld".toByteArray()
        val framed = FakeSocket.frame(2, message)
        val expected = "B0x2\n0xb\nhello\nworld\n".toByteArray()
        assertArrayEquals(expected, framed)
    }

    @Test
    fun frameHexSerialAndLengthWithTrailingNewline() {
        val message = ByteArray(300) { 'a'.code.toByte() }
        val framed = FakeSocket.frame(26, message)
        val expectedHeader = "T0x1a\n0x12c\n".toByteArray()
        val expected = expectedHeader + message + '\n'.code.toByte()
        assertArrayEquals(expected, framed)
        assertEquals('\n'.code.toByte(), framed.last())
    }

    @Test
    fun openReturnsMobileAndResetsDrainNumbering() {
        val socket = FakeSocket()
        socket.enqueue("first".toByteArray())
        socket.drain()

        val openReply = socket.open()
        assertArrayEquals("mobile".toByteArray(), openReply)

        socket.enqueue("second".toByteArray())
        val drained = socket.drain()
        val expected = FakeSocket.frame(1, "second".toByteArray())
        assertArrayEquals(expected, drained)
    }

    @Test
    fun drainFramesQueuedMessagesInOrder() {
        val socket = FakeSocket()
        val msg1 = "alpha".toByteArray()
        val msg2 = "beta\ngamma".toByteArray()
        socket.enqueue(msg1)
        socket.enqueue(msg2)

        val drained = socket.drain()
        val expected = FakeSocket.frame(1, msg1) + FakeSocket.frame(2, msg2)
        assertArrayEquals(expected, drained)
    }

    @Test
    fun drainReturnsEmptyWhenNothingQueued() {
        val socket = FakeSocket()
        assertArrayEquals(ByteArray(0), socket.drain())
    }
}
