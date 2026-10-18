package com.keyling.hub.protocol

import bridge.Credits

/**
 * The outgoing CMD byte stream under the dongle's credit-based flow control.
 *
 * Frames are queued whole and taken out as writes that never exceed the credit balance, so a
 * frame may be spread over several writes and a write may hold several frames.
 */
class CmdStream {
    private val queue = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var awaitingReset = true

    /** Bytes the phone may still write. */
    var credits = 0
        private set

    /** Queued bytes not yet taken by [nextWrite]. */
    var pendingBytes = 0
        private set

    fun enqueue(frame: ByteArray) {
        if (frame.isEmpty()) return
        queue.addLast(frame)
        pendingBytes += frame.size
    }

    /**
     * Applies a Credits event. A reset replaces the balance; increments are ignored until the
     * first reset after [reset], since they were granted for bytes the dongle discarded.
     */
    fun onCredits(event: Credits) {
        if (event.reset) {
            credits = event.bytes
            awaitingReset = false
        } else if (!awaitingReset) {
            credits += event.bytes
        }
    }

    /** Takes the next write of at most [maxSize] bytes, or null if nothing can be sent now. */
    fun nextWrite(maxSize: Int): ByteArray? {
        val size = minOf(maxSize, credits, pendingBytes)
        if (size <= 0) return null
        val write = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val head = queue.first()
            val n = minOf(size - filled, head.size - headOffset)
            head.copyInto(write, filled, headOffset, headOffset + n)
            filled += n
            headOffset += n
            if (headOffset == head.size) {
                queue.removeFirst()
                headOffset = 0
            }
        }
        credits -= size
        pendingBytes -= size
        return write
    }

    /**
     * Drops everything queued and zeroes the balance until the next Credits reset: after an
     * abort on CTRL, and when a session starts.
     */
    fun reset() {
        queue.clear()
        headOffset = 0
        pendingBytes = 0
        credits = 0
        awaitingReset = true
    }
}
