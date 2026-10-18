package com.keyling.hub.protocol

import bridge.Config
import bridge.Event
import bridge.GetConfig
import bridge.Hello
import bridge.Request
import bridge.Result
import bridge.SetConfig
import bridge.Status
import bridge.Strokes
import bridge.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString

const val PROTOCOL_VERSION = 1

/** Request families named in `Hello.capabilities`. */
object Capability {
    const val TEXT = "text"
    const val STROKES = "strokes"
    const val KEY_COMBO = "key_combo"
    const val CONSUMER = "consumer"
    const val KEYBOARD_REPORT = "keyboard_report"
    const val CONFIG = "config"
    const val OTA = "ota"
}

/** Room left in a frame for the Request and Text fields around the text itself. */
private const val TEXT_FRAME_OVERHEAD = 16

/** The bridge characteristics of one connection, as seen by [Session]. */
interface Transport {
    /** Largest CMD write for this connection. */
    val writeSize: Int

    /** Writes [bytes] to CMD, returning once the stack has taken them. */
    suspend fun writeCmd(bytes: ByteArray)

    /** Writes to CTRL. */
    suspend fun writeCtrl()
}

class UnsupportedProtocolException(version: Int) :
    ProtocolException("dongle speaks protocol $version, this app speaks $PROTOCOL_VERSION")

class SessionClosedException(cause: Throwable? = null) : ProtocolException("session closed", cause)

/**
 * One protocol session over [transport], from enabling EVT notifications until disconnect.
 *
 * Feed EVT notifications to [onEvt]. Requests are queued at once and written as credits allow,
 * so several calls may be in flight together; each call returns when its Acks arrive.
 */
class Session(private val transport: Transport, scope: CoroutineScope) {
    private val lock = Any()
    private val decoder = EventDecoder()
    private val stream = CmdStream()
    private val pending = HashMap<Int, CompletableDeferred<Result>>()
    private var nextId = 1
    private var closedCause: Throwable? = null

    private val writeMutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val helloEvent = CompletableDeferred<Hello>()
    private val _status = MutableStateFlow<Status?>(null)
    private val _configs = MutableSharedFlow<Config>(extraBufferCapacity = 8)

    /** The latest Status event. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** Config events, sent in reply to GetConfig and SetConfig. */
    val configs: SharedFlow<Config> = _configs.asSharedFlow()

    private val writer = scope.launch {
        try {
            for (signal in wake) {
                @Suppress("ControlFlowWithEmptyBody")
                while (writeNext()) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            close(e)
        }
    }

    private suspend fun writeNext(): Boolean = writeMutex.withLock {
        val write = synchronized(lock) { stream.nextWrite(transport.writeSize) }
            ?: return@withLock false
        transport.writeCmd(write)
        true
    }

    /** Handles bytes notified on EVT. Throws [ProtocolException] on a malformed stream. */
    fun onEvt(bytes: ByteArray) {
        val events = synchronized(lock) { decoder.feed(bytes) }
        events.forEach(::dispatch)
    }

    private fun dispatch(event: Event) {
        when {
            event.hello != null -> {
                val h = event.hello
                if (h.protocol_version != PROTOCOL_VERSION) {
                    close(UnsupportedProtocolException(h.protocol_version))
                } else {
                    helloEvent.complete(h)
                }
            }
            event.credits != null -> {
                synchronized(lock) { stream.onCredits(event.credits) }
                wake.trySend(Unit)
            }
            event.ack != null -> {
                val waiter = synchronized(lock) { pending.remove(event.ack.id) }
                waiter?.complete(event.ack.result)
            }
            event.status != null -> _status.value = event.status
            event.config != null -> _configs.tryEmit(event.config)
        }
    }

    /** Waits for the session's Hello. */
    suspend fun hello(): Hello = helloEvent.await()

    suspend fun supports(capability: String): Boolean = capability in hello().capabilities

    /**
     * Sends [requests] in order and waits for all their Acks. Returns RESULT_OK, or the first
     * other result. Requests pending when [abort] is called return RESULT_ABORTED.
     */
    suspend fun call(requests: List<Request>): Result {
        val maxFrame = hello().max_frame
        val waiters = synchronized(lock) {
            closedCause?.let { throw SessionClosedException(it) }
            val frames = requests.map { request ->
                val id = nextId
                nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
                id to encodeFrame(request.copy(id = id), maxFrame)
            }
            frames.map { (id, frame) ->
                stream.enqueue(frame)
                CompletableDeferred<Result>().also { pending[id] = it }
            }
        }
        wake.trySend(Unit)
        var result = Result.RESULT_OK
        for (waiter in waiters) {
            val r = waiter.await()
            if (result == Result.RESULT_OK) result = r
        }
        return result
    }

    suspend fun call(request: Request): Result = call(listOf(request))

    /** Types [text] with the dongle's layout. */
    suspend fun typeText(text: String): Result {
        require(supports(Capability.TEXT)) { "dongle does not support text" }
        val limit = minOf(MAX_TEXT_BYTES, hello().max_frame - TEXT_FRAME_OVERHEAD)
        return call(splitText(text, limit).map { Request(text = Text(it)) })
    }

    /** Types packed `[modifiers, keycode, flags]` strokes. */
    suspend fun typeStrokes(strokes: ByteArray): Result {
        require(supports(Capability.STROKES)) { "dongle does not support strokes" }
        return call(splitStrokes(strokes).map { Request(strokes = Strokes(it.toByteString())) })
    }

    suspend fun getConfig(): Config = configReply(Request(get_config = GetConfig()))

    /** Changes the fields present in [config] and returns the resulting configuration. */
    suspend fun setConfig(config: Config): Config = configReply(Request(set_config = SetConfig(config)))

    private suspend fun configReply(request: Request): Config = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) { configs.first() }
        val result = call(request)
        if (result != Result.RESULT_OK) {
            reply.cancel()
            throw ProtocolException("config request failed: $result")
        }
        reply.await()
    }

    /**
     * Stops typing and drops all input the dongle has not processed. Pending calls return
     * RESULT_ABORTED; new calls are sent once the dongle grants credits again.
     */
    suspend fun abort() {
        writeMutex.withLock {
            val aborted = synchronized(lock) {
                stream.reset()
                pending.values.toList().also { pending.clear() }
            }
            aborted.forEach { it.complete(Result.RESULT_ABORTED) }
            transport.writeCtrl()
        }
    }

    /** Ends the session: pending and later calls fail with [SessionClosedException]. */
    fun close(cause: Throwable? = null) {
        val failed = synchronized(lock) {
            if (closedCause != null) return
            closedCause = cause ?: SessionClosedException()
            stream.reset()
            pending.values.toList().also { pending.clear() }
        }
        val error = SessionClosedException(cause)
        helloEvent.completeExceptionally(error)
        failed.forEach { it.completeExceptionally(error) }
        writer.cancel()
        wake.close()
    }
}
