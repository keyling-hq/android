package com.keyling.hub.protocol

import bridge.Ack
import bridge.Config
import bridge.Credits
import bridge.Event
import bridge.Hello
import bridge.Request
import bridge.Result
import bridge.Status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {
    private class FakeTransport(override val writeSize: Int = 20) : Transport {
        val writes = mutableListOf<ByteArray>()
        var ctrlWrites = 0
        var failWrites = false

        override suspend fun writeCmd(bytes: ByteArray) {
            if (failWrites) throw IOException("link lost")
            writes += bytes
        }

        override suspend fun writeCtrl() {
            ctrlWrites++
        }

        /** Requests decoded from everything written so far. */
        fun requests(): List<Request> {
            val stream = writes.fold(ByteArray(0), ByteArray::plus)
            val requests = mutableListOf<Request>()
            var i = 0
            while (i < stream.size) {
                val length = (stream[i].toInt() and 0xFF) or ((stream[i + 1].toInt() and 0xFF) shl 8)
                requests += Request.ADAPTER.decode(stream.copyOfRange(i + 2, i + 2 + length))
                i += 2 + length
            }
            return requests
        }
    }

    private fun frame(event: Event): ByteArray {
        val payload = Event.ADAPTER.encode(event)
        return byteArrayOf(payload.size.toByte(), (payload.size ushr 8).toByte()) + payload
    }

    private val hello = Hello(
        protocol_version = PROTOCOL_VERSION,
        max_frame = 1100,
        firmware_version = "1.0",
        capabilities = listOf(Capability.TEXT, Capability.STROKES, Capability.CONFIG),
    )

    private fun TestScope.start(transport: FakeTransport, credits: Int = 4096): Session {
        val session = Session(transport, backgroundScope)
        session.onEvt(frame(Event(hello = hello)) + frame(Event(credits = Credits(bytes = credits, reset = true))))
        return session
    }

    private fun Session.ack(id: Int, result: Result = Result.RESULT_OK) =
        onEvt(frame(Event(ack = Ack(id = id, result = result))))

    @Test
    fun typeTextSplitsAndWaitsForEveryAck() = runTest {
        val transport = FakeTransport()
        val session = start(transport)
        val text = "a".repeat(2000)

        val call = async { session.typeText(text) }
        runCurrent()
        val requests = transport.requests()
        assertEquals(listOf(1, 2), requests.map { it.id })
        assertEquals(text, requests.joinToString("") { it.text!!.text })
        assertTrue(transport.writes.all { it.size <= 20 })

        session.ack(1)
        runCurrent()
        assertFalse(call.isCompleted)
        session.ack(2)
        assertEquals(Result.RESULT_OK, call.await())
    }

    @Test
    fun returnsFirstFailure() = runTest {
        val session = start(FakeTransport())
        val call = async { session.typeText("a".repeat(2000)) }
        runCurrent()
        session.ack(2, Result.RESULT_FAILED)
        session.ack(1)
        assertEquals(Result.RESULT_FAILED, call.await())
    }

    @Test
    fun waitsForCredits() = runTest {
        val transport = FakeTransport(writeSize = 512)
        val session = start(transport, credits = 10)

        backgroundScope.async { session.typeText("hello world, this is longer than ten bytes") }
        runCurrent()
        assertEquals(10, transport.writes.sumOf { it.size })

        session.onEvt(frame(Event(credits = Credits(bytes = 1000))))
        runCurrent()
        assertEquals(1, transport.requests().size)
    }

    @Test
    fun abortCompletesPendingAndWaitsForReset() = runTest {
        val transport = FakeTransport()
        val session = start(transport, credits = 10)

        val call = async { session.typeText("this will be aborted") }
        runCurrent()
        session.abort()
        assertEquals(Result.RESULT_ABORTED, call.await())
        assertEquals(1, transport.ctrlWrites)

        val before = transport.writes.size
        session.onEvt(frame(Event(credits = Credits(bytes = 100))))
        backgroundScope.async { session.typeText("next") }
        runCurrent()
        assertEquals(before, transport.writes.size)

        session.onEvt(frame(Event(credits = Credits(bytes = 4096, reset = true))))
        runCurrent()
        assertEquals(before + 1, transport.writes.size)
    }

    @Test
    fun configReplyComesFromConfigEvent() = runTest {
        val session = start(FakeTransport())
        val call = async { session.setConfig(Config(layout = "es-419")) }
        runCurrent()
        val reply = Config(layout = "es-419", key_press_ms = 5, key_release_ms = 5)
        session.onEvt(frame(Event(config = reply)))
        session.ack(1)
        assertEquals(reply, call.await())
    }

    @Test
    fun statusIsTracked() = runTest {
        val session = start(FakeTransport())
        session.onEvt(frame(Event(status = Status(typing = true, usb_mounted = true, layout = "en-US"))))
        assertTrue(session.status.value!!.typing)
    }

    @Test(expected = SessionClosedException::class)
    fun closeFailsPendingCalls() = runTest {
        val session = start(FakeTransport())
        val call = async { session.typeText("hello") }
        runCurrent()
        session.close()
        call.await()
    }

    @Test(expected = SessionClosedException::class)
    fun writeFailureClosesSession() = runTest {
        val transport = FakeTransport().apply { failWrites = true }
        val session = start(transport)
        session.typeText("hello")
    }

    @Test(expected = SessionClosedException::class)
    fun rejectsOtherProtocolVersion() = runTest {
        val session = Session(FakeTransport(), backgroundScope)
        session.onEvt(frame(Event(hello = hello.copy(protocol_version = PROTOCOL_VERSION + 1))))
        session.hello()
    }
}
