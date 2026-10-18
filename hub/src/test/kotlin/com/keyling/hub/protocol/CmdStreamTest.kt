package com.keyling.hub.protocol

import bridge.Credits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CmdStreamTest {
    private fun bytes(range: IntRange) = ByteArray(range.count()) { (range.first + it).toByte() }

    private fun drain(stream: CmdStream, maxSize: Int): List<ByteArray> =
        generateSequence { stream.nextWrite(maxSize) }.toList()

    @Test
    fun sendsNothingBeforeFirstReset() {
        val stream = CmdStream()
        stream.enqueue(bytes(0..9))
        stream.onCredits(Credits(bytes = 100))
        assertNull(stream.nextWrite(512))
        assertEquals(0, stream.credits)
    }

    @Test
    fun writesSpanFramesAndRespectMaxSize() {
        val stream = CmdStream()
        stream.onCredits(Credits(bytes = 4096, reset = true))
        stream.enqueue(bytes(0..4))
        stream.enqueue(bytes(5..11))

        val writes = drain(stream, 4)
        assertEquals(listOf(4, 4, 4), writes.map { it.size })
        assertArrayEquals(bytes(0..11), writes.reduce(ByteArray::plus))
        assertEquals(4096 - 12, stream.credits)
        assertEquals(0, stream.pendingBytes)
    }

    @Test
    fun stopsAtCreditBalanceAndResumesOnIncrement() {
        val stream = CmdStream()
        stream.onCredits(Credits(bytes = 6, reset = true))
        stream.enqueue(bytes(0..9))

        assertArrayEquals(bytes(0..5), stream.nextWrite(512))
        assertNull(stream.nextWrite(512))
        assertEquals(4, stream.pendingBytes)

        stream.onCredits(Credits(bytes = 10))
        assertArrayEquals(bytes(6..9), stream.nextWrite(512))
        assertEquals(6, stream.credits)
    }

    @Test
    fun resetReplacesBalance() {
        val stream = CmdStream()
        stream.onCredits(Credits(bytes = 100, reset = true))
        stream.onCredits(Credits(bytes = 50))
        stream.onCredits(Credits(bytes = 30, reset = true))
        assertEquals(30, stream.credits)
    }

    @Test
    fun abortDropsQueueAndIgnoresStaleIncrements() {
        val stream = CmdStream()
        stream.onCredits(Credits(bytes = 4096, reset = true))
        stream.enqueue(bytes(0..9))
        stream.nextWrite(3)

        stream.reset()
        assertEquals(0, stream.pendingBytes)
        stream.onCredits(Credits(bytes = 3))
        assertEquals(0, stream.credits)

        stream.onCredits(Credits(bytes = 4096, reset = true))
        stream.enqueue(bytes(20..21))
        assertArrayEquals(bytes(20..21), stream.nextWrite(512))
    }
}
