package com.keyling.hub.protocol

import bridge.Ack
import bridge.Credits
import bridge.Event
import bridge.Hello
import bridge.Request
import bridge.Result
import bridge.Text
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {
    private fun eventFrame(event: Event): ByteArray {
        val payload = Event.ADAPTER.encode(event)
        return byteArrayOf(payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
    }

    private val hello = Event(
        hello = Hello(protocol_version = 1, max_frame = 1100, firmware_version = "1.0", capabilities = listOf("text")),
    )
    private val credits = Event(credits = Credits(bytes = 4096, reset = true))
    private val ack = Event(ack = Ack(id = 7, result = Result.RESULT_OK))

    @Test
    fun encodesLittleEndianLengthPrefix() {
        val request = Request(id = 1, text = Text("x".repeat(300)))
        val payload = Request.ADAPTER.encode(request)
        val frame = encodeFrame(request)

        assertEquals(payload.size, (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8))
        assertArrayEquals(payload, frame.copyOfRange(2, frame.size))
        assertEquals(request, Request.ADAPTER.decode(payload))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadOverMaxFrame() {
        encodeFrame(Request(text = Text("x".repeat(100))), maxPayload = 50)
    }

    @Test
    fun decodesSeveralFramesFromOneNotification() {
        val stream = eventFrame(hello) + eventFrame(credits) + eventFrame(ack)
        assertEquals(listOf(hello, credits, ack), EventDecoder().feed(stream))
    }

    @Test
    fun decodesFramesFedOneByteAtATime() {
        val stream = eventFrame(hello) + eventFrame(credits) + eventFrame(ack)
        val decoder = EventDecoder()
        val events = stream.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(hello, credits, ack), events)
    }

    @Test
    fun keepsPartialFrameUntilComplete() {
        val frame = eventFrame(hello)
        val decoder = EventDecoder()
        assertTrue(decoder.feed(frame.copyOfRange(0, 5)).isEmpty())
        assertEquals(listOf(hello), decoder.feed(frame.copyOfRange(5, frame.size)))
    }

    @Test
    fun decodesEmptyFrame() {
        assertEquals(listOf(Event()), EventDecoder().feed(byteArrayOf(0, 0)))
    }

    @Test
    fun resetDropsPartialFrame() {
        val decoder = EventDecoder()
        decoder.feed(eventFrame(hello).copyOfRange(0, 4))
        decoder.reset()
        assertEquals(listOf(ack), decoder.feed(eventFrame(ack)))
    }

    @Test(expected = ProtocolException::class)
    fun rejectsMalformedFrame() {
        EventDecoder().feed(byteArrayOf(2, 0, 0x0A, 0x05))
    }

    @Test
    fun writeSizeIsCappedAt512() {
        assertEquals(20, writeSize(23))
        assertEquals(244, writeSize(247))
        assertEquals(512, writeSize(517))
    }
}
