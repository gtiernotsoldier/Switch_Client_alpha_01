package io.doppel.core.ipc

import io.doppel.core.model.VelocityContext
import io.doppel.core.strategy.velocity.VelocityResult
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where the core process listens. */
sealed class Endpoint {
    data class Tcp(val host: String, val port: Int) : Endpoint()

    /** Linux/macOS only. Windows and Java 8 have no UDS; see [CoreStub.connect]. */
    data class Unix(val path: String) : Endpoint()

    companion object {
        fun parse(s: String): Endpoint {
            if (s.startsWith("tcp:")) {
                val rest = s.removePrefix("tcp:")
                val i = rest.lastIndexOf(':')
                require(i > 0) { "bad tcp endpoint: $s" }
                return Tcp(rest.substring(0, i), rest.substring(i + 1).toInt())
            }
            if (s.startsWith("unix:")) return Unix(s.removePrefix("unix:"))
            throw IllegalArgumentException("bad endpoint: $s")
        }
    }
}

/**
 * Byte pipe. Deliberately dumb: it knows frames, not messages.
 *
 * `readFully` returns false only for a clean close at a frame boundary. A close in
 * the middle of a frame is an [IOException] — the peer died holding half a
 * message, and silently treating that as EOF would let a truncated stream look
 * like a graceful shutdown.
 */
internal interface Channel : Closeable {
    fun readFully(buf: ByteArray): Boolean
    fun write(buf: ByteArray)
}

private class StreamChannel(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) : Channel {
    override fun readFully(buf: ByteArray): Boolean {
        var filled = 0
        while (filled < buf.size) {
            val n = input.read(buf, filled, buf.size - filled)
            if (n < 0) {
                if (filled == 0) return false
                throw IOException("peer closed mid-frame ($filled/${buf.size})")
            }
            filled += n
        }
        return true
    }

    override fun write(buf: ByteArray) {
        output.write(buf)
        output.flush()
    }

    override fun close() {
        try {
            closer()
        } catch (_: Exception) {
        }
    }
}

/**
 * The game-side thin stub: snapshot out, decision back, one round trip per tick.
 *
 * Threading. Snapshot writes happen on the tick thread; a dedicated reader thread
 * drains replies and hands each one to the [Pending] slot that is waiting for its
 * sequence number. This is why a deadline miss does not corrupt the stream: the
 * read side is never cancelled, so a late reply is simply discarded when its slot
 * is already gone. A blocking read with `SO_TIMEOUT` would have been simpler and
 * wrong — after a timeout it is undefined how much of a frame has been consumed.
 *
 * Degradation (PROTECTION.md 9.5.3). A miss, a dead socket, or a `Degraded`
 * status all return **Pass(original motion)**, never the previous command. For a
 * Modify-type command, replaying last tick's vector would apply last tick's scale
 * factor to this tick's knockback, and the position error would stack instead of
 * staying bounded at one tick. Skipping the tick is the conservative reading of
 * "hold the previous command", and it is what keeps the worst case at "one
 * missed reduction" rather than "a growing deviation".
 */
class CoreStub(
    private val endpoint: Endpoint,
    private val token: String? = null,
    /** ~1 ms: about 67× the measured p99 envelope, per 9.5.3. */
    private val deadlineNanos: Long = 1_000_000L,
    private val handshakeTimeoutMillis: Int = 500
) : Closeable {

    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    @Volatile
    private var channel: Channel? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    var lastError: String? = null
        private set

    val timeouts = AtomicLong(0)
    val degradedTicks = AtomicLong(0)
    val roundTrips = AtomicLong(0)
    var reconnects: Long = 0
        private set

    @Volatile
    var sessionId: Long = 0
        private set

    @Volatile
    var seed: Long = 0
        private set

    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var frame: Frame? = null
    }

    /**
     * Connects and completes the handshake. Throws on failure — a stub that
     * cannot reach the core must not pretend it can decide.
     */
    fun connect() {
        val ch = open()
        channel = ch

        // Handshake happens before the reader thread starts: two readers on one
        // stream would race for the ack.
        val tokenBytes = (token ?: "").toByteArray(Charsets.UTF_8)
        val hello = Frame(Wire.KIND_HELLO, seq.getAndIncrement(), 0, payload = tokenBytes.copyOf(Wire.MAX_PAYLOAD), payloadLen = tokenBytes.size)
        ch.write(hello.encode())

        val ackBytes = ByteArray(Wire.FRAME)
        if (!ch.readFully(ackBytes)) {
            ch.close()
            throw IOException("core closed during handshake")
        }
        val ack = Frame.decode(ackBytes) ?: run {
            ch.close()
            throw IOException("malformed handshake reply")
        }
        if (ack.kind != Wire.KIND_HELLO_ACK) {
            ch.close()
            throw IOException("handshake rejected: status=${ack.status}")
        }
        val b = java.nio.ByteBuffer.wrap(ack.payload, 0, ack.payloadLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        sessionId = b.long
        seed = b.long

        connected.set(true)
        running.set(true)
        startReader(ch)
    }

    private fun startReader(ch: Channel) {
        val t = Thread({
            val buf = ByteArray(Wire.FRAME)
            try {
                while (running.get()) {
                    if (!ch.readFully(buf)) break
                    val f = Frame.decode(buf) ?: continue
                    val slot = pending.remove(f.seq)
                    if (slot != null) {
                        slot.frame = f
                        slot.latch.countDown()
                    }
                    // No slot: the caller already gave up on this sequence. Drop it.
                }
            } catch (e: Exception) {
                if (running.get()) lastError = e.message
            } finally {
                connected.set(false)
                // Unblock every waiter immediately instead of letting them burn
                // their full deadline on a socket that is already gone.
                val it = pending.values.iterator()
                while (it.hasNext()) {
                    it.next().latch.countDown()
                }
                pending.clear()
            }
        }, "doppel-core-reader")
        t.isDaemon = true
        t.start()
        reader = t
    }

    /**
     * One tick. Never blocks longer than [deadlineNanos].
     */
    fun decide(ctx: VelocityContext, tick: Int): VelocityResult {
        if (!connected.get()) {
            if (!tryReconnect()) {
                degradedTicks.incrementAndGet()
                return VelocityResult.Pass(ctx.originalMotion)
            }
        }
        val ch = channel ?: return VelocityResult.Pass(ctx.originalMotion)

        val s = seq.getAndIncrement()
        val slot = Pending()
        pending[s] = slot

        val payload = encodeSnapshot(ctx)
        try {
            ch.write(Frame(Wire.KIND_SNAPSHOT, s, tick, payload = payload, payloadLen = payload.size).encode())
        } catch (e: IOException) {
            pending.remove(s)
            lastError = e.message
            connected.set(false)
            degradedTicks.incrementAndGet()
            return VelocityResult.Pass(ctx.originalMotion)
        }

        val arrived = slot.latch.await(deadlineNanos, TimeUnit.NANOSECONDS)
        pending.remove(s)

        val f = slot.frame
        if (!arrived || f == null) {
            timeouts.incrementAndGet()
            degradedTicks.incrementAndGet()
            return VelocityResult.Pass(ctx.originalMotion)
        }
        if (f.status != Wire.STATUS_OK) {
            degradedTicks.incrementAndGet()
            return VelocityResult.Pass(ctx.originalMotion)
        }

        roundTrips.incrementAndGet()
        val d = decodeDecision(f)
        return when (d.action) {
            Wire.ACTION_PASS -> VelocityResult.Pass(ctx.originalMotion)
            Wire.ACTION_MODIFY -> VelocityResult.Modify(d.motion)
            Wire.ACTION_CLICK_BURST -> VelocityResult.ClickBurst(d.targetId, d.clicks)
            Wire.ACTION_CANCEL -> VelocityResult.Cancel(ctx.packetHandle)
            else -> VelocityResult.NoOp
        }
    }

    /** Pushes a config snapshot. Fire-and-forget: the core acknowledges with a Config frame. */
    fun pushConfig(payload: ByteArray) {
        val ch = channel ?: return
        try {
            ch.write(Frame(Wire.KIND_CONFIG, seq.getAndIncrement(), 0, payload = payload, payloadLen = payload.size).encode())
        } catch (e: IOException) {
            lastError = e.message
            connected.set(false)
        }
    }

    private fun tryReconnect(): Boolean {
        synchronized(this) {
            if (connected.get()) return true
            reconnects++
            return try {
                closeQuietly()
                connect()
                true
            } catch (e: Exception) {
                lastError = e.message
                false
            }
        }
    }

    private fun open(): Channel {
        when (val ep = endpoint) {
            is Endpoint.Tcp -> {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ep.host, ep.port), handshakeTimeoutMillis)
                return StreamChannel(s.getInputStream(), s.getOutputStream()) { s.close() }
            }
            is Endpoint.Unix -> {
                // Reflected on purpose: this class is compiled for Java 8 (the game
                // runs 1.8.9), where java.net.UnixDomainSocketAddress does not exist.
                // A direct reference would fail at class-load time on Java 8 even
                // though the TCP path is all it ever uses.
                val addrClass = Class.forName("java.net.UnixDomainSocketAddress")
                val address = addrClass.getMethod("of", String::class.java).invoke(null, ep.path)
                val ch = SocketChannel.open()
                ch.connect(address as java.net.SocketAddress)
                return StreamChannel(Channels.newInputStream(ch), Channels.newOutputStream(ch)) { ch.close() }
            }
        }
    }

    private fun closeQuietly() {
        running.set(false)
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        channel = null
        connected.set(false)
        pending.clear()
    }

    override fun close() {
        val ch = channel
        if (ch != null) {
            try {
                ch.write(Frame(Wire.KIND_BYE, seq.getAndIncrement(), 0).encode())
            } catch (_: Exception) {
            }
        }
        closeQuietly()
    }
}
