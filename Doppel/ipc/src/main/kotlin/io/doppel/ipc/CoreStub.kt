package io.doppel.ipc

import io.doppel.contract.model.VelocityContext
import io.doppel.contract.strategy.velocity.VelocityResult
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
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.util.Vec3

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
     * One round trip. Returns the decision frame, or null after a miss.
     *
     * Every module funnels through here so the deadline, the reconnect and the
     * degradation accounting have exactly one implementation. A miss, a dead
     * socket and a `Degraded` status all collapse to null: each caller decides
     * what "no answer" means for its own command type, and none of them is
     * allowed to guess.
     */
    private fun roundTrip(module: Int, payload: ByteArray, tick: Int): Frame? {
        if (!connected.get()) {
            if (!tryReconnect()) {
                degradedTicks.incrementAndGet()
                return null
            }
        }
        val ch = channel ?: run {
            degradedTicks.incrementAndGet()
            return null
        }

        val s = seq.getAndIncrement()
        val slot = Pending()
        pending[s] = slot

        try {
            ch.write(
                Frame(
                    Wire.KIND_SNAPSHOT, s, tick,
                    module = module,
                    payload = payload,
                    payloadLen = payload.size
                ).encode()
            )
        } catch (e: IOException) {
            pending.remove(s)
            lastError = e.message
            connected.set(false)
            degradedTicks.incrementAndGet()
            return null
        }

        val arrived = slot.latch.await(deadlineNanos, TimeUnit.NANOSECONDS)
        pending.remove(s)

        val f = slot.frame
        if (!arrived || f == null) {
            timeouts.incrementAndGet()
            degradedTicks.incrementAndGet()
            return null
        }
        if (f.status != Wire.STATUS_OK) {
            degradedTicks.incrementAndGet()
            return null
        }
        roundTrips.incrementAndGet()
        return f
    }

    /**
     * Velocity, one tick. Never blocks longer than [deadlineNanos].
     *
     * Degradation (PROTECTION.md 9.5.3): a miss, a dead socket, or a `Degraded`
     * status all return **Pass(original motion)**, never the previous command.
     * Replaying last tick's vector would apply last tick's scale factor to this
     * tick's knockback, and the position error would stack instead of staying
     * bounded at one tick.
     */
    fun decide(ctx: VelocityContext, tick: Int): VelocityResult {
        val d = decideFrame(ctx, tick) ?: return VelocityResult.Pass(ctx.originalMotion)
        return when (d.action) {
            Wire.ACTION_PASS -> VelocityResult.Pass(ctx.originalMotion)
            Wire.ACTION_MODIFY -> VelocityResult.Modify(d.motion)
            Wire.ACTION_CLICK_BURST -> VelocityResult.ClickBurst(d.targetId, d.clicks)
            Wire.ACTION_CANCEL -> VelocityResult.Cancel(ctx.packetHandle)
            else -> VelocityResult.NoOp
        }
    }

    /**
     * One velocity hit, keeping the reason code.
     *
     * SPRINT's answer is always "pass the packet through", so the only way to know whether it also
     * armed a brace window is the reason (`sprint_armed`). Collapsing this to [VelocityResult]
     * would make the module unable to tell "no brace" from "braced", and the HUD flash and the
     * wall-clock deadline both hang off that distinction.
     */
    fun decideFrame(ctx: VelocityContext, tick: Int): DecisionFrame? {
        val f = roundTrip(Wire.MODULE_VELOCITY, encodeSnapshot(ctx), tick) ?: return null
        return decodeDecision(f)
    }

    /**
     * One tick of the sprint brace: which keys to hold, as `[forward, back, left, right]`.
     *
     * The armed window and its remaining ticks live in the core, so this call consumes one. Null
     * means no answer — the caller then releases the keys, which is the safe direction: a stuck
     * brace walks the player into the knockback.
     */
    fun velocityBrace(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        yaw: Float,
        sector: Double,
        tick: Int
    ): BooleanArray? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.VEL_BRACE, yaw.toDouble(), sector
        )
        val f = roundTrip(Wire.MODULE_VELOCITY, payload, tick) ?: return null
        return decodeSuppress(f)
    }

    /**
     * KnockbackDelay: hold this knockback back, or let it through?
     *
     * The delay parameters travel with the request rather than a config frame: Velocity and
     * KnockbackDelay are separate modules sharing one velocity session, and a config push from one
     * would replace the other's settings mid-flight.
     */
    fun velocityDelayEnqueue(
        ctx: io.doppel.contract.model.VelocityContext,
        tickNow: Int,
        chance: Int,
        maxDelayTicks: Int,
        requireWeapon: Boolean,
        options: io.doppel.contract.option.TriggerOptions,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(ctx), Ops.VEL_DELAY_ENQUEUE,
            tickNow.toDouble(), chance.toDouble(), maxDelayTicks.toDouble(),
            b(requireWeapon), encodeTriggerFlags(options),
            options.minDistance.toDouble(), options.maxDistance.toDouble(),
            options.lookAngleThreshold.toDouble(), options.chance.toDouble()
        )
        val f = roundTrip(Wire.MODULE_VELOCITY, payload, tick) ?: return null
        return decodeBool(f)
    }

    /** KnockbackDelay: which held motions have come due this tick. */
    fun velocityDelayPump(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        tickNow: Int,
        tick: Int
    ): List<io.doppel.contract.util.Vec3>? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.VEL_DELAY_PUMP, tickNow.toDouble()
        )
        val f = roundTrip(Wire.MODULE_VELOCITY, payload, tick) ?: return null
        return decodeMotions(f)
    }

    /**
     * Aim, one tick. Null means the core did not answer — the caller must skip
     * the tick rather than re-apply the last rotation, for the same reason
     * velocity passes instead of replaying.
     */
    fun decideAim(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        mouseDeltaX: Float,
        mouseDeltaY: Float,
        sensitivity: Float,
        tick: Int
    ): AimDecision? {
        val snap = encodeSnapshot(player, target)
        val f = roundTrip(
            Wire.MODULE_AIM,
            withAimTail(snap, mouseDeltaX, mouseDeltaY, sensitivity),
            tick
        ) ?: return null
        return decodeAimDecision(f)
    }

    /** Click, one tick. A miss degrades to Skip — never to a synthetic click. */
    fun decideClick(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        attackCooldown: Float,
        isFalling: Boolean,
        tick: Int
    ): io.doppel.contract.strategy.click.ClickResult {
        val snap = encodeSnapshot(player, target)
        val f = roundTrip(
            Wire.MODULE_CLICK,
            withClickTail(snap, attackCooldown, isFalling),
            tick
        ) ?: return io.doppel.contract.strategy.click.ClickResult.Skip
        return decodeClickDecision(f)
    }

    /**
     * Movement's three predicates. Each returns null on a miss, and a null
     * suppression means "do not touch the keys" — the conservative reading.
     */
    fun movementSocd(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        forward: Boolean,
        back: Boolean,
        left: Boolean,
        right: Boolean,
        tick: Int
    ): BooleanArray? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.MOV_SOCD,
            b(forward), b(back), b(left), b(right)
        )
        val f = roundTrip(Wire.MODULE_MOVEMENT, payload, tick) ?: return null
        return decodeSuppress(f)
    }

    fun movementStrafe(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        forward: Boolean,
        back: Boolean,
        left: Boolean,
        right: Boolean,
        distance: Double,
        bearingRad: Double,
        yawDeg: Double,
        reach: Double,
        tick: Int
    ): BooleanArray? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.MOV_STRAFE,
            b(forward), b(back), b(left), b(right),
            distance, bearingRad, yawDeg, reach
        )
        val f = roundTrip(Wire.MODULE_MOVEMENT, payload, tick) ?: return null
        return decodeSuppress(f)
    }

    fun movementSliding(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        motionX: Double,
        motionZ: Double,
        yaw: Float,
        releasedForward: Boolean,
        minSpeed: Double,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.MOV_SLIDING,
            motionX, motionZ, yaw.toDouble(), b(releasedForward), minSpeed
        )
        val f = roundTrip(Wire.MODULE_MOVEMENT, payload, tick) ?: return null
        return decodeBool(f)
    }

    /** Tap side, or -1.0 when the core did not answer or there is no target. */
    fun tapChase(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        yaw: Float,
        leadTicks: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.TAP_CHASE,
            yaw.toDouble(), leadTicks.toDouble()
        )
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    fun tapSeven(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        lastSide: Int,
        repeatChancePercent: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.TAP_SEVEN,
            lastSide.toDouble(), repeatChancePercent.toDouble()
        )
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    /** Uniform side draw from the core's tap stream. -1.0 when there is no core. */
    fun tapRandom(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        tick: Int
    ): Double? {
        val payload = genericRequest(encodeSnapshot(player, target), Ops.TAP_RANDOM)
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    fun tapWrap(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        angle: Float,
        tick: Int
    ): Double? {
        val payload = genericRequest(encodeSnapshot(player, target), Ops.TAP_WRAP, angle.toDouble())
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    /**
     * Advances the tap state machine. 0 = none, 1 = end tap, 2 = should start.
     */
    fun tapTick(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        tick: Int
    ): Double? {
        val payload = genericRequest(encodeSnapshot(player, target), Ops.TAP_TICK, nowNs.toDouble())
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    fun tapBegin(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        durationMs: Int,
        postDelay: Boolean,
        tick: Int
    ) {
        val payload = genericRequest(
            encodeSnapshot(player, target),
            if (postDelay) Ops.TAP_POST_DELAY else Ops.TAP_BEGIN,
            nowNs.toDouble(), durationMs.toDouble()
        )
        roundTrip(Wire.MODULE_TAP, payload, tick)
    }

    /**
     * WTap / STap range sampling. One packed scalar carries the post-delay,
     * the tap duration and the initial hit threshold; [io.doppel.ipc.TapSample]
     * unpacks it. Null when no core is attached — callers then do nothing
     * rather than acting on a local guess.
     */
    fun tapSample(
        tapOp: Int,
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        kind: Int,
        postDelayMin: Int,
        postDelayMax: Int,
        actionMin: Int,
        actionMax: Int,
        onceEveryMin: Int,
        onceEveryMax: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), tapOp,
            kind.toDouble(), postDelayMin.toDouble(), postDelayMax.toDouble(),
            actionMin.toDouble(), actionMax.toDouble(),
            onceEveryMin.toDouble(), onceEveryMax.toDouble()
        )
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    /**
     * ADTap's fire-point chance roll plus its duration / gap draws; one packed
     * scalar carries (duration, gap, passed), which [io.doppel.ipc.ADTapSample]
     * unpacks. Null when no core is attached — the module then taps nothing
     * rather than guessing locally.
     */
    fun adtapSample(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        kind: Int,
        chance: Int,
        tapMinMs: Int,
        tapMaxMs: Int,
        gapMinMs: Int,
        gapMaxMs: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.TAP_ADTAP_SAMPLE,
            kind.toDouble(), chance.toDouble(),
            tapMinMs.toDouble(), tapMaxMs.toDouble(),
            gapMinMs.toDouble(), gapMaxMs.toDouble()
        )
        val f = roundTrip(Wire.MODULE_TAP, payload, tick) ?: return null
        return decodeScalar(f)
    }

    fun combatTrigger(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        mode: Int,
        targetHurtResistantTime: Int,
        maxHurtTime: Int,
        hitCounter: Int,
        hitThreshold: Int,
        hitPerMin: Int,
        hitPerMax: Int,
        chance: Int,
        hurtGate: Boolean,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_TRIGGER,
            mode.toDouble(), targetHurtResistantTime.toDouble(), maxHurtTime.toDouble(),
            hitCounter.toDouble(), hitThreshold.toDouble(), hitPerMin.toDouble(),
            hitPerMax.toDouble(), chance.toDouble(), b(hurtGate)
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * The condition gate and the combat trigger as one decision.
     *
     * `mode < 0` runs the gate alone — that is what the modules that only ask "may I act this
     * tick" need (JumpReset, Sprint, SuperKnockback...). `mode` 0/1/2 is POST/PRE/EQUAL and adds
     * the hurt-window gate, the hit counter and the probability roll.
     *
     * One op rather than two because both halves draw from the same stream in the core. Splitting
     * them would let the gate's roll and the trigger's draws interleave differently on each side,
     * and no replay would survive 300 ticks.
     */
    fun combatDecide(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        options: io.doppel.contract.option.TriggerOptions,
        mode: Int,
        hurtGate: Boolean,
        maxHurtTime: Int,
        targetHurtResistantTime: Int,
        hitCounter: Int,
        hitThreshold: Int,
        hitPerMin: Int,
        hitPerMax: Int,
        triggerChance: Int,
        tick: Int
    ): CombatDecision? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_DECIDE,
            encodeTriggerFlags(options),
            options.minDistance.toDouble(),
            options.maxDistance.toDouble(),
            options.lookAngleThreshold.toDouble(),
            options.chance.toDouble(),
            mode.toDouble(),
            b(hurtGate),
            maxHurtTime.toDouble(),
            targetHurtResistantTime.toDouble(),
            hitCounter.toDouble(),
            hitThreshold.toDouble(),
            hitPerMin.toDouble(),
            hitPerMax.toDouble(),
            triggerChance.toDouble()
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeCombatDecision(f)
    }

    fun combatArbiter(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        locked: Boolean,
        threat: Boolean,
        sustain: Boolean,
        reflex: Boolean,
        wLock: Double,
        wThreat: Double,
        wSustain: Double,
        wReflex: Double,
        threshold: Double,
        maxDuty: Double,
        tick: Int
    ): Verdict? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_ARBITER,
            b(locked), b(threat), b(sustain), b(reflex),
            wLock, wThreat, wSustain, wReflex, threshold, maxDuty
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeVerdict(f)
    }

    fun combatPredict(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        maxHurt: Int,
        rtt: Int,
        lead: Int,
        predict: Boolean,
        window: Int,
        chance: Int,
        alpha: Double,
        seedInterval: Double,
        currentTick: Int,
        opponentJustHit: Boolean,
        tick: Int
    ): Verdict? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_PREDICT,
            maxHurt.toDouble(), rtt.toDouble(), lead.toDouble(), b(predict),
            window.toDouble(), chance.toDouble(), alpha, seedInterval,
            currentTick.toDouble(), b(opponentJustHit)
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeVerdict(f)
    }

    fun combatRange(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        mode: Int,
        minDistance: Float,
        maxDistance: Float,
        hysteresis: Float,
        distance: Float?,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_RANGE,
            mode.toDouble(), minDistance.toDouble(), maxDistance.toDouble(),
            hysteresis.toDouble(), b(distance != null), (distance ?: 0f).toDouble()
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * AutoBlock's chance gates — the per-tick Chance roll and the Lag window's
     * start roll — on AutoBlock's own RNG stream. Null when no core is
     * attached; callers then do nothing rather than acting on a local guess.
     */
    fun autoblockChance(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        chance: Int,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_AUTOBLOCK_CHANCE, chance.toDouble()
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * BlockHit's range sampling — the post-delay and block-duration draws at
     * the fire point, and the hit-threshold draw on enable; one packed scalar
     * carries all three, which [io.doppel.ipc.BlockHitSample] unpacks. Null
     * when no core is attached — the module then blocks nothing rather than
     * guessing locally.
     */
    fun blockhitSample(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        kind: Int,
        postDelayMin: Int,
        postDelayMax: Int,
        waitMin: Int,
        waitMax: Int,
        hitPerMin: Int,
        hitPerMax: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.CBT_BLOCKHIT_SAMPLE,
            kind.toDouble(),
            postDelayMin.toDouble(), postDelayMax.toDouble(),
            waitMin.toDouble(), waitMax.toDouble(),
            hitPerMin.toDouble(), hitPerMax.toDouble()
        )
        val f = roundTrip(Wire.MODULE_COMBAT, payload, tick) ?: return null
        return decodeScalar(f)
    }

    fun reachIntersect(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        box: io.doppel.contract.model.Hitbox,
        origin: io.doppel.contract.util.Vec3,
        dir: io.doppel.contract.util.Vec3,
        maxDistance: Double,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.REACH_INTERSECT,
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
            origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, maxDistance
        )
        val f = roundTrip(Wire.MODULE_REACH, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * Eagle's sneak machine: one call per tick.
     *
     * Returns packed flags — bit0 press, bit1 release, bit2 the core owns the
     * sneak — or null on a miss. A null must mean "write nothing at all": a
     * stale press would leave the sneak key down with nobody owning it, and a
     * stale release would cancel a sneak the player is holding by hand.
     */
    fun playerEagle(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        onEdge: Boolean,
        settingsMet: Boolean,
        sneakKeyHeld: Boolean,
        minDelay: Int,
        maxDelay: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_EAGLE_TICK,
            b(onEdge), b(settingsMet), b(sneakKeyHeld),
            minDelay.toDouble(), maxDelay.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * Eagle AutoPlace's placement step: one call per tick.
     *
     * The aim-in-flight flag, the ticks spent waiting and the placement
     * cooldown (including its random draw) are core state. This side sends the
     * two judgements it made (gate + pitch tolerance) and the cooldown bounds,
     * and gets back a step code: 0 none, 1 aim, 2 click, 3 abort.
     *
     * `null` means the core did not answer. The module then writes no rotation
     * and no right-click — starting an aim or placing a block without the
     * core's decision is the one thing that must never happen by accident.
     */
    fun playerEaglePlace(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        gatesOpen: Boolean,
        pitchInTolerance: Boolean,
        bridgeAssistEnabled: Boolean,
        maxWaitTicks: Int,
        cooldownMin: Int,
        cooldownMax: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_EAGLE_PLACE,
            b(gatesOpen), b(pitchInTolerance), b(bridgeAssistEnabled),
            maxWaitTicks.toDouble(), cooldownMin.toDouble(), cooldownMax.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * SprintReset's per-tick reset decision: one call per tick.
     *
     * The rising-edge bit, the hit-count throttle, the target id and the
     * delayed-send deadline (including the chance roll) are core state. This
     * side sends only the judgements it made (target present, range, gate,
     * hurt) plus the tuning knobs, and gets back a step code: 0 none,
     * 1 + mode send (mode 0 Nostop, 1 Silent).
     *
     * `null` means the core did not answer. The module then sends no sprint
     * reset — inventing a packet burst without the core's decision is the one
     * thing that must never happen by accident.
     */
    fun playerSprintReset(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        hasTarget: Boolean,
        inRange: Boolean,
        gateOk: Boolean,
        isHurt: Boolean,
        entityId: Int,
        chance: Int,
        hitCount: Int,
        delayMs: Int,
        mode: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_SPRINT_RESET,
            nowNs.toDouble(), b(hasTarget), b(inRange), b(gateOk), b(isHurt),
            entityId.toDouble(), chance.toDouble(), hitCount.toDouble(),
            delayMs.toDouble(), mode.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * JumpReset knock: one call per knockback packet (velocity/Netty thread).
     *
     * The JVM has already combined every internal condition and the unified
     * gate into `gatesOpen`; the core owns the probability roll, the cooldown
     * counter (Ticks / ReceivedHits), and the delayed-jump deadline. Returns a
     * step code: 0 = no immediate jump (gates closed, chance rejected,
     * cooldown not met, or a jump was armed for a later tick), 1 = jump now.
     *
     * `null` means the core did not answer — the caller queues no jump.
     */
    fun playerJumpKnock(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        gatesOpen: Boolean,
        nowNs: Long,
        chance: Int,
        cooldownMode: Int,
        ticksUntil: Int,
        hitsUntil: Int,
        delayMs: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_JUMP_KNOCK,
            b(gatesOpen), nowNs.toDouble(), chance.toDouble(),
            cooldownMode.toDouble(), ticksUntil.toDouble(), hitsUntil.toDouble(),
            delayMs.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * JumpReset pump: one call per client tick (main thread). Advances the
     * Ticks cooldown and fires a delayed jump at its deadline. Never draws.
     *
     * `null` means the core did not answer — the caller queues no jump.
     */
    fun playerJumpPump(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        cooldownMode: Int,
        ticksUntil: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_JUMP_PUMP,
            nowNs.toDouble(), cooldownMode.toDouble(), ticksUntil.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * SuperKnockback: one tick of the phase machine.
     *
     * The JVM has already evaluated every judgement (crosshair target present,
     * the trigger style, the distance window, the unified gate, the server
     * sprint mirror and the coordination flag) and marshals them with the
     * tuning knobs. The core owns the Idle / Delay / SprintTap phase, the delay
     * deadline, the coordination offset, the SprintTap force sequence, the
     * per-target hit-count throttle and the chance roll.
     *
     * The scalar answer is a packed code: low nibble = the SprintTap
     * force-sprint step at the top of the tick, high nibble = the fire step
     * from the trigger evaluation. Both are platform writes, so a tick can
     * carry two of them. `null` means no answer — the caller writes nothing.
     */
    fun playerSuperKnockback(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        hasTarget: Boolean,
        triggerOk: Boolean,
        inRange: Boolean,
        gateOk: Boolean,
        entityId: Int,
        chance: Int,
        hitCount: Int,
        delayMs: Int,
        mode: Int,
        sprintTapAllowed: Boolean,
        coordinationActive: Boolean,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_SUPER_KNOCKBACK,
            nowNs.toDouble(), b(hasTarget), b(triggerOk), b(inRange), b(gateOk),
            entityId.toDouble(), chance.toDouble(), hitCount.toDouble(),
            delayMs.toDouble(), mode.toDouble(), b(sprintTapAllowed), b(coordinationActive)
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * KeepSprint: one frame of the hold machine.
     *
     * The JVM supplies the frame's judgements — the dispatch tick, the
     * sprint sample, the attack composite, the crosshair target being
     * alive, the forward-key state and the tap-owns-W override — and the
     * core owns the sprint-sample latch, the two-dispatch hold window, the
     * wall-clock backstop and the per-tick assert dedup.
     *
     * The scalar answer is packed: low nibble = the hold *status* (0 dead,
     * 1 live, 2 release), high nibble = the assert step (0 none, 1 assert).
     * DEAD is not merely "nothing happened" — it is what lets the adapter
     * give back a claim the core no longer knows about. `null` means no
     * answer: the caller writes nothing and releases any claim it holds.
     */
    fun playerKeepSprint(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        tick: Int,
        localSprinting: Boolean,
        attacking: Boolean,
        targetAlive: Boolean,
        forward: Boolean,
        forwardOverride: Boolean,
        requireSprinting: Boolean,
        tickId: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_KEEPSPRINT,
            nowNs.toDouble(), tick.toDouble(), b(localSprinting), b(attacking),
            b(targetAlive), b(forward), b(forwardOverride), b(requireSprinting)
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tickId) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * LagRange: one tick of the packet-hold machine.
     *
     * The JVM supplies the judgements (GUI open, the target's distance, the
     * weapon being a sword) and the player snapshot fields (hurt time,
     * sprinting, blocking), and marshals the knobs (range / maxDelay / the
     * three condition toggles). The core owns the previous distance, the
     * damage / sprint / block edges and the hold's start timestamp.
     *
     * The answer is a step code: 0 none, 1 hold, 2 arm (hold just started —
     * flash the HUD), 3 flush. `null` means no answer — the caller must let
     * the packets through: holding is the state that can desync the view,
     * so the safe direction when the core is unreachable is not to hold.
     */
    fun playerLagRange(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        guiOpen: Boolean,
        hasDist: Boolean,
        dist: Double,
        range: Double,
        maxDelayMs: Int,
        sprintResetOn: Boolean,
        isSprinting: Boolean,
        blockSwordOn: Boolean,
        isBlocking: Boolean,
        holdingWeaponOn: Boolean,
        weaponIsSword: Boolean,
        hurtTime: Int,
        tick: Int
    ): Int? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_LAGRANGE,
            nowNs.toDouble(), b(guiOpen), b(hasDist), dist, range,
            maxDelayMs.toDouble(), b(sprintResetOn), b(isSprinting),
            b(blockSwordOn), b(isBlocking), b(holdingWeaponOn),
            b(weaponIsSword), hurtTime.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toInt()
    }

    /**
     * Backtrack's hold window: one call per tick.
     *
     * `null` means the core did not answer. The caller must then let the
     * updates through — holding is the state that can desync the view, so the
     * safe direction when the core is unreachable is not to hold at all.
     */
    fun playerBacktrack(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        modern: Boolean,
        pulse: Boolean,
        smart: Boolean,
        minDelayMs: Int,
        maxDelayMs: Int,
        distance: Float,
        legacyDistance: Float,
        mustRelease: Boolean,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_BACKTRACK_TICK,
            nowNs.toDouble(), b(modern), b(pulse), b(smart),
            minDelayMs.toDouble(), maxDelayMs.toDouble(),
            distance.toDouble(), legacyDistance.toDouble(), b(mustRelease)
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * KnockbackDisplace: a landed hit. Returns the yaw offset in degrees, or 0
     * for "no flick".
     *
     * The cooldown, the direction and the strafe memory all live in the core;
     * the adapter only decides when to write and restore the rotation.
     */
    fun playerKbdAttack(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        direction: Int,
        useLastStrafe: Boolean,
        cooldownMs: Int,
        angle: Float,
        notTakingKnockback: Boolean,
        velocityThisTick: Boolean,
        keyLeft: Boolean,
        keyRight: Boolean,
        tick: Int
    ): Float? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_KBD_ATTACK,
            nowNs.toDouble(), direction.toDouble(), b(useLastStrafe),
            cooldownMs.toDouble(), angle.toDouble(),
            b(notTakingKnockback), b(velocityThisTick), b(keyLeft), b(keyRight)
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f).toFloat()
    }

    /**
     * Reach: sample one press. Returns the distance to use, or 0.0 to leave the
     * press alone.
     *
     * The throttle counter and the cooldown live in the core; the caller only
     * reports the gates and the configured range.
     */
    fun playerReachSample(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        conditionsOk: Boolean,
        guiOpen: Boolean,
        chance: Int,
        hitPer: Int,
        delayMs: Int,
        hitThroughBlocks: Boolean,
        lookingAtBlock: Boolean,
        reachMin: Float,
        reachMax: Float,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_REACH_SAMPLE,
            nowNs.toDouble(), b(conditionsOk), b(guiOpen),
            chance.toDouble(), hitPer.toDouble(), delayMs.toDouble(),
            b(hitThroughBlocks), b(lookingAtBlock),
            reachMin.toDouble(), reachMax.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f)
    }

    /**
     * ClickAssist: evaluate one physical press. Returns how many extra clicks
     * to emit (0, 1 or 2).
     *
     * The 500 ms edge history and both rolls live in the core; the caller only
     * reports the thirteen predicates as a bit field and the configured mode.
     */
    fun playerClickAssist(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        flags: Int,
        probability: Int,
        mode: Int,
        tick: Int
    ): Double? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_CLICK_ASSIST,
            nowNs.toDouble(), flags.toDouble(), probability.toDouble(), mode.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeScalar(f)
    }

    /**
     * HitSelect, tick half: feed the target view and refresh the windows.
     * Returns whether CounterHit should fire this tick; the one-tick pulse
     * itself is a platform concern and stays in the adapter.
     */
    fun playerHitTick(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        canCrit: Boolean,
        playerHurtTime: Int,
        leftMouseDown: Boolean,
        hasTarget: Boolean,
        targetId: Int,
        targetHurtTime: Int,
        counterHit: Boolean,
        counterCdMs: Int,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_HIT_TICK,
            nowNs.toDouble(), b(canCrit), playerHurtTime.toDouble(), b(leftMouseDown),
            b(hasTarget), targetId.toDouble(), targetHurtTime.toDouble(),
            b(counterHit), counterCdMs.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeBool(f)
    }

    /**
     * HitSelect, gate half: one click. Returns whether the click should be
     * delivered. The windows it reads were opened by [playerHitTick] on the
     * same session.
     */
    fun playerHitGate(
        player: io.doppel.contract.model.PlayerState,
        target: io.doppel.contract.model.TargetState?,
        nowNs: Long,
        guiOpen: Boolean,
        lookingAtBlock: Boolean,
        chance: Int,
        criticals: Boolean,
        missedSwing: Int,
        hasForwardTarget: Boolean,
        forwardHurtResistantTime: Int,
        waitFirstMs: Int,
        retimeAt: Int,
        pauseMs: Int,
        tick: Int
    ): Boolean? {
        val payload = genericRequest(
            encodeSnapshot(player, target), Ops.PLAYER_HIT_GATE,
            nowNs.toDouble(), b(guiOpen), b(lookingAtBlock), chance.toDouble(),
            b(criticals), missedSwing.toDouble(), b(hasForwardTarget),
            forwardHurtResistantTime.toDouble(), waitFirstMs.toDouble(),
            retimeAt.toDouble(), pauseMs.toDouble()
        )
        val f = roundTrip(Wire.MODULE_PLAYER, payload, tick) ?: return null
        return decodeBool(f)
    }

    private fun b(v: Boolean): Double = if (v) 1.0 else 0.0

    /**
     * Drops one module's cross-tick memory in the core.
     *
     * Called on module disable and on enable, because both leave the core
     * holding facts that stopped being true: a key that is no longer held, a tap
     * whose remaining time belongs to the previous activation, the previous
     * player's motion. Fire-and-forget like [pushConfig] — the next request
     * carries its own inputs, and a lost reset degrades to "one stale tick",
     * not to a wrong answer forever.
     */
    fun reset(module: Int) {
        val ch = channel ?: return
        try {
            ch.write(
                Frame(
                    Wire.KIND_RESET, seq.getAndIncrement(), 0,
                    module = module, payload = ByteArray(0), payloadLen = 0
                ).encode()
            )
        } catch (e: IOException) {
            lastError = e.message
            connected.set(false)
        }
    }

    /** Pushes a config snapshot. Fire-and-forget: the core acknowledges with a Config frame. */
    fun pushConfig(payload: ByteArray) = pushConfig(Wire.MODULE_VELOCITY, payload)

    /**
     * Pushes a config for one module.
     *
     * Fire-and-forget on purpose: a config is a handful of scalars that change
     * when the user drags a slider, not per tick. Waiting for the ack would put
     * a round trip on the UI thread for no benefit — and if it is lost, the next
     * push carries the same values.
     */
    fun pushConfig(module: Int, payload: ByteArray) {
        val ch = channel ?: return
        try {
            ch.write(
                Frame(
                    Wire.KIND_CONFIG, seq.getAndIncrement(), 0,
                    module = module, payload = payload, payloadLen = payload.size
                ).encode()
            )
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
