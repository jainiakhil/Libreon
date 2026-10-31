// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.office

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The engine-to-front-end half of the front-end's fake websocket.
 *
 * The engine's messages are queued here; the front-end is then nudged to fetch them with a
 * request to `cool://…/mobilesocket/…`, answered with every queued message in the framing
 * its mobile socket expects (see [frame]).
 */
internal class FakeSocket {
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private var serial = 0

    fun enqueue(message: ByteArray) {
        queue.add(message)
    }

    /** The front-end opened its socket: the answer is the socket's name, and numbering restarts. */
    @Synchronized
    fun open(): ByteArray {
        serial = 0
        return OPEN_REPLY
    }

    /** Everything queued so far, framed. */
    @Synchronized
    fun drain(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val message = queue.poll() ?: break
            out.write(frame(++serial, message))
        }
        return out.toByteArray()
    }

    companion object {
        private val OPEN_REPLY = "mobile".toByteArray()

        /**
         * One message as `<type>0x<serial>\n0x<length>\n<message>\n`, serial and length in
         * lowercase hex. The type is `B` (binary) when the message contains a newline, else `T`.
         */
        fun frame(serial: Int, message: ByteArray): ByteArray {
            val type = if (message.contains('\n'.code.toByte())) 'B' else 'T'
            val header = "${type}0x${serial.toString(16)}\n0x${message.size.toString(16)}\n".toByteArray()
            return header + message + '\n'.code.toByte()
        }
    }
}
