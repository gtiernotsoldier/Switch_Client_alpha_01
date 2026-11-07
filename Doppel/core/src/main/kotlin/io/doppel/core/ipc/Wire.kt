package io.doppel.core.ipc

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.model.VelocityContext
import io.doppel.core.strategy.click.WeaponType
import io.doppel.core.util.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format v1, byte-for-byte the same as `core-rs/src/wire.rs`.
 *
 * The layout is documented in `Doppel/docs/IPC-PROTOCOL.md`. Two invariants are
 * worth restating because both sides depend on them:
 *
 *  - **fixed 256-byte frames.** The reader is a single `readFully` into a stack
 *    buffer; there is no length prefix to get out of sync and no allocation per
 *    tick.
 *  - **little-endian, raw float bits.** `Float.floatToRawIntBits` here and
 *    `f32::to_bits` there produce the same four bytes, which is what makes a
 *    replay diff meaningful rather than "close enough".
 *
 * This side never validates *semantics* — only the framing. A well-formed frame
 * with a nonsensical module id is the core's problem, and it answers with an
 * error frame rather than the stub guessing.
 */
object Wire {
    const val FRAME = 256
    const val HEADER = 32
    const val MAX_PAYLOAD = FRAME - HEADER

    const val MAGIC = 0x44504C31
    const val VERSION = 1

    // kinds
    const val KIND_HELLO = 1
    const val KIND_HELLO_ACK = 2
    const val KIND_SNAPSHOT = 3
    const val KIND_DECISION = 4
    const val KIND_CONFIG = 5
    const val KIND_PING = 6
    const val KIND_PONG = 7
    const val KIND_ERROR = 8
    const val KIND_BYE = 9

    // status
    const val STATUS_OK = 0
    const val STATUS_BAD_VERSION = 1
    const val STATUS_BAD_TOKEN = 2
    const val STATUS_UNKNOWN_KIND = 3
    const val STATUS_BAD_PAYLOAD = 4
    const val STATUS_DEGRADED = 5
    const val STATUS_NO_SESSION = 6

    // modules
    const val MODULE_VELOCITY = 1

    // actions
    const val ACTION_NOOP = 0
    const val ACTION_PASS = 1
    const val ACTION_MODIFY = 2
    const val ACTION_CLICK_BURST = 3
    const val ACTION_CANCEL = 4

    const val SLOT_SNAPSHOT = 160
    const val SLOT_CONFIG = 104
    const val SLOT_DECISION = 40

    fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

/** One decoded frame. `payload` is always [Wire.MAX_PAYLOAD] bytes; only [payloadLen] are meaningful. */
class Frame(
    @JvmField val kind: Int,
    @JvmField val seq: Int,
    @JvmField val tick: Int,
    @JvmField val status: Int = Wire.STATUS_OK,
    @JvmField val module: Int = Wire.MODULE_VELOCITY,
    @JvmField val payload: ByteArray = ByteArray(Wire.MAX_PAYLOAD),
    @JvmField val payloadLen: Int = 0,
    @JvmField val correlation: Long = 0L
) {
    fun encode(): ByteArray {
        val b = Wire.le(Wire.FRAME)
        b.putInt(Wire.MAGIC)
        b.putShort(Wire.VERSION.toShort())
        b.putShort(kind.toShort())
        b.putInt(seq)
        b.putInt(tick)
        b.putShort(payloadLen.toShort())
        b.putShort(status.toShort())
        b.put(module.toByte())
        b.put(0.toByte())      // flags
        b.putShort(0)          // reserved
        b.putLong(correlation)
        require(b.position() == Wire.HEADER) { "header must be exactly ${Wire.HEADER} bytes" }
        b.put(payload, 0, payloadLen)
        return b.array()
    }

    companion object {
        /** Returns null on a framing error — the caller drops the connection. */
        fun decode(bytes: ByteArray): Frame? {
            if (bytes.size != Wire.FRAME) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (b.int != Wire.MAGIC) return null
            if (b.short.toInt() != Wire.VERSION) return null
            val kind = b.short.toInt()
            val seq = b.int
            val tick = b.int
            val len = b.short.toInt()
            val status = b.short.toInt()
            val module = b.get().toInt()
            b.get()
            b.short
            val correlation = b.long
            if (len < 0 || len > Wire.MAX_PAYLOAD) return null
            val payload = ByteArray(Wire.MAX_PAYLOAD)
            b.get(payload, 0, len)
            return Frame(kind, seq, tick, status, module, payload, len, correlation)
        }
    }
}

/**
 * Encodes the tick snapshot the core needs. Field order must match
 * `session::decode_snapshot` exactly; the offset table in IPC-PROTOCOL.md is the
 * shared reference if the two ever disagree.
 */
fun encodeSnapshot(ctx: VelocityContext, flagsOverride: Int? = null): ByteArray {
    val p = ctx.player
    val b = Wire.le(Wire.SLOT_SNAPSHOT)
    b.put(0.toByte())                         // mode (informational; config wins)
    b.put((if (ctx.isKnockbackHit) 1 else 0).toByte())
    b.putShort((flagsOverride ?: packFlags(p)).toShort())
    // f64, not f32: these feed the velocity multiplier directly. Routing them
    // through f32 shifts the product by a few ulp, which is invisible in play and
    // fatal to a bit-exact replay.
    b.putDouble(ctx.originalMotion.x)
    b.putDouble(ctx.originalMotion.y)
    b.putDouble(ctx.originalMotion.z)
    b.putDouble(p.position.x)
    b.putDouble(p.position.y)
    b.putDouble(p.position.z)
    b.putDouble(p.motionX)
    b.putDouble(p.motionY)
    b.putDouble(p.motionZ)
    b.putFloat(p.rotation.yaw)
    b.putFloat(p.rotation.pitch)
    b.putFloat(p.health)
    b.putInt(p.hurtTime)
    b.putInt(p.hurtResistantTime)
    b.put(encodeWeapon(p.weaponType))
    val t = ctx.target
    b.put((if (t != null) 1 else 0).toByte())
    b.putShort(0)
    if (t != null) {
        b.putInt(t.id)
        b.putDouble(t.position.x)
        b.putDouble(t.position.y)
        b.putDouble(t.position.z)
        b.putFloat(t.distance)
        b.putFloat(t.health)
        b.putInt(t.hurtTime)
        b.put((if (t.isMovingBackward) 1 else 0).toByte())
        b.put((if (t.isMovingTowardsPlayer) 1 else 0).toByte())
        b.putShort(0)
    } else {
        b.put(ByteArray(44))   // target block is always present, zeroed: 4+24+4+4+4+1+1+2
    }
    b.putInt((p.ticks and 0xFFFF_FFFFL).toInt())
    b.putInt(0)   // packet id — the stub assigns it when it registers the packet
    b.putInt(0)   // config revision
    b.putInt(0)
    require(b.position() == Wire.SLOT_SNAPSHOT) { "snapshot must be ${Wire.SLOT_SNAPSHOT} bytes, got ${b.position()}" }
    return b.array()
}

private const val F_ON_GROUND = 1 shl 0
private const val F_MOVING = 1 shl 1
private const val F_MOVING_FORWARD = 1 shl 2
private const val F_SPRINTING = 1 shl 3
private const val F_LOOKING_AT_TARGET = 1 shl 4
private const val F_BLOCKING = 1 shl 5
private const val F_USING_ITEM = 1 shl 6
private const val F_SNEAKING = 1 shl 7
private const val F_ATTACK_KEY_DOWN = 1 shl 8
private const val F_MINING = 1 shl 9

private fun packFlags(p: PlayerState): Int {
    var f = 0
    if (p.onGround) f = f or F_ON_GROUND
    if (p.isMoving) f = f or F_MOVING
    if (p.isMovingForward) f = f or F_MOVING_FORWARD
    if (p.isSprinting) f = f or F_SPRINTING
    if (p.isLookingAtTarget) f = f or F_LOOKING_AT_TARGET
    if (p.isBlocking) f = f or F_BLOCKING
    if (p.isUsingItem) f = f or F_USING_ITEM
    if (p.isSneaking) f = f or F_SNEAKING
    if (p.isAttackKeyDown) f = f or F_ATTACK_KEY_DOWN
    if (p.isMining) f = f or F_MINING
    return f
}

private fun encodeWeapon(w: WeaponType): Byte = when (w) {
    WeaponType.SWORD -> 1
    WeaponType.AXE -> 2
    else -> 0
}

/** A decision as it comes off the wire, before it is mapped to a `VelocityResult`. */
class DecisionFrame(
    @JvmField val action: Int,
    @JvmField val reason: Int,
    @JvmField val flags: Int,
    @JvmField val motion: Vec3,
    @JvmField val targetId: Int,
    @JvmField val clicks: Int,
    @JvmField val tick: Int
)

fun decodeDecision(f: Frame): DecisionFrame {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    val action = b.get().toInt()
    val reason = b.get().toInt()
    val flags = b.short.toInt()
    val mx = b.double
    val my = b.double
    val mz = b.double
    val targetId = b.int
    val clicks = b.int
    val tick = b.int
    return DecisionFrame(action, reason, flags, Vec3(mx, my, mz), targetId, clicks, tick)
}

fun reasonName(r: Int): String = when (r) {
    1 -> "condition_failed"
    2 -> "hit_frame_filter"
    3 -> "gate_skip"
    4 -> "modified"
    5 -> "no_target"
    6 -> "hurt_time_mismatch"
    7 -> "facing"
    8 -> "out_of_range"
    9 -> "delayed"
    10 -> "released"
    11 -> "pass_through"
    12 -> "panic"
    else -> "none"
}
