package com.keyling.hub.protocol

import bridge.Event
import bridge.Request
import java.io.IOException

/** Largest single ATT write the dongle accepts, whatever the negotiated MTU. */
const val MAX_WRITE_BYTES = 512

/** Size of each CMD write for a negotiated ATT MTU. */
fun writeSize(mtu: Int): Int = minOf(mtu - 3, MAX_WRITE_BYTES)

private const val LENGTH_BYTES = 2
private const val MAX_PAYLOAD = 0xFFFF

open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Encodes [request] as a CMD frame: `[length: u16 little endian][Request]`.
 * [maxPayload] is the dongle's `Hello.max_frame`.
 */
fun encodeFrame(request: Request, maxPayload: Int = MAX_PAYLOAD): ByteArray {
    val payload = Request.ADAPTER.encode(request)
    require(payload.size <= minOf(maxPayload, MAX_PAYLOAD)) {
        "request payload is ${payload.size} bytes, limit is $maxPayload"
    }
    val frame = ByteArray(LENGTH_BYTES + payload.size)
    frame[0] = payload.size.toByte()
    frame[1] = (payload.size ushr 8).toByte()
    payload.copyInto(frame, LENGTH_BYTES)
    return frame
}

/** Reassembles [Event] frames from EVT notifications, which may split or join frames anywhere. */
class EventDecoder {
    private var buffer = ByteArray(256)
    private var size = 0

    /** Appends [bytes] to the stream and returns the events completed by them. */
    fun feed(bytes: ByteArray): List<Event> {
        if (size + bytes.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + bytes.size))
        }
        bytes.copyInto(buffer, size)
        size += bytes.size

        val events = mutableListOf<Event>()
        var start = 0
        while (size - start >= LENGTH_BYTES) {
            val length = (buffer[start].toInt() and 0xFF) or
                ((buffer[start + 1].toInt() and 0xFF) shl 8)
            val end = start + LENGTH_BYTES + length
            if (end > size) break
            events += try {
                Event.ADAPTER.decode(buffer.copyOfRange(start + LENGTH_BYTES, end))
            } catch (e: IOException) {
                throw ProtocolException("malformed event frame", e)
            }
            start = end
        }
        buffer.copyInto(buffer, 0, start, size)
        size -= start
        return events
    }

    /** Drops any partial frame, for a new session. */
    fun reset() {
        size = 0
    }
}
