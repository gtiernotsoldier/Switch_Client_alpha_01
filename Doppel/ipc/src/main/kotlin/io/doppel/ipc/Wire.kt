package io.doppel.ipc

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.doppel.contract.model.Hitbox
import io.doppel.contract.option.AimMode
import io.doppel.contract.option.ClickMode
import io.doppel.contract.option.TriggerOptions
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickOperatingMode
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.CooldownClickConfig
import io.doppel.contract.strategy.click.CooldownClickMode
import io.doppel.contract.strategy.click.CritMode
import io.doppel.contract.strategy.velocity.VelocityResult

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
    /**
     * 384, not 256. Velocity was the only module when the frame was 256: its
     * snapshot is 160 bytes and the rest was headroom. Aim needs the target's
     * per-tick velocity and world-space box — the predictive lead reads the
     * first, the multipoint reads the second, and a default player box makes the
     * core's answer diverge from the in-process one for any entity that is not a
     * standing player. That is 72 more bytes, and the generic modules need an op
     * plus up to 14 arguments behind the snapshot. One shape beats three.
     */
    const val FRAME = 384
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
    /** Forget one module's cross-tick state; the module id is in the header. */
    const val KIND_RESET = 10

    // status
    const val STATUS_OK = 0
    const val STATUS_BAD_VERSION = 1
    const val STATUS_BAD_TOKEN = 2
    const val STATUS_UNKNOWN_KIND = 3
    const val STATUS_BAD_PAYLOAD = 4
    const val STATUS_DEGRADED = 5
    const val STATUS_NO_SESSION = 6

    // modules — append-only; a number never changes meaning
    const val MODULE_VELOCITY = 1
    const val MODULE_CLICK = 2
    const val MODULE_AIM = 3
    const val MODULE_MOVEMENT = 4
    const val MODULE_TAP = 5
    const val MODULE_COMBAT = 6
    const val MODULE_REACH = 7
    const val MODULE_PLAYER = 8

    // actions
    const val ACTION_NOOP = 0
    /** Reason codes that a caller may branch on (`decodeDecision(...).reason`). */
    const val REASON_SPRINT_ARMED = 13

    const val ACTION_PASS = 1
    const val ACTION_MODIFY = 2
    const val ACTION_CLICK_BURST = 3
    const val ACTION_CANCEL = 4
    const val ACTION_APPLY_ROTATION = 5
    const val ACTION_CLICK = 6
    const val ACTION_STOP_SPRINT = 7
    const val ACTION_RESTORE_SPRINT = 8
    const val ACTION_SUPPRESS = 9
    const val ACTION_TAP_SIDE = 10
    const val ACTION_BLOCK = 11
    const val ACTION_SCALAR = 12
    const val ACTION_BOOL = 13

    const val SLOT_SNAPSHOT = 232
    const val SLOT_CONFIG = 104
    const val SLOT_DECISION = 40

    /** Bytes an aim request appends to the snapshot. */
    const val AIM_TAIL = 12
    /** Bytes a click request appends to the snapshot. */
    const val CLICK_TAIL = 8
    /** Bytes an aim decision occupies. */
    const val AIM_DECISION = 97

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
 *
 * The target block carries the target's own velocity and world box as well as
 * its position. Aiming reads both: the predictive lead extrapolates the motion,
 * and the multipoint picks a point on the box. Omitting them and letting the
 * core substitute defaults works only for a standing player — a sneaking one is
 * 0.3 blocks shorter and a moving one leads the wrong way.
 */
fun encodeSnapshot(
    player: PlayerState,
    target: TargetState?,
    originalMotion: Vec3 = Vec3(player.motionX, player.motionY, player.motionZ),
    isKnockbackHit: Boolean = true,
    flagsOverride: Int? = null
): ByteArray {
    val b = Wire.le(Wire.SLOT_SNAPSHOT)
    b.put(0.toByte())                         // mode (informational; config wins)
    b.put((if (isKnockbackHit) 1 else 0).toByte())
    b.putShort((flagsOverride ?: packFlags(player)).toShort())
    // f64, not f32: these feed the velocity multiplier directly. Routing them
    // through f32 shifts the product by a few ulp, which is invisible in play and
    // fatal to a bit-exact replay.
    b.putDouble(originalMotion.x)
    b.putDouble(originalMotion.y)
    b.putDouble(originalMotion.z)
    b.putDouble(player.position.x)
    b.putDouble(player.position.y)
    b.putDouble(player.position.z)
    b.putDouble(player.motionX)
    b.putDouble(player.motionY)
    b.putDouble(player.motionZ)
    b.putFloat(player.rotation.yaw)
    b.putFloat(player.rotation.pitch)
    b.putFloat(player.health)
    b.putInt(player.hurtTime)
    b.putInt(player.hurtResistantTime)
    b.put(encodeWeapon(player.weaponType))
    b.put((if (target != null) 1 else 0).toByte())
    b.putShort(0)
    if (target != null) {
        b.putInt(target.entityId)
        b.putDouble(target.position.x)
        b.putDouble(target.position.y)
        b.putDouble(target.position.z)
        b.putDouble(target.motionX)
        b.putDouble(target.motionY)
        b.putDouble(target.motionZ)
        b.putFloat(target.distance)
        b.putFloat(target.health)
        b.putInt(target.hurtTime)
        b.put((if (target.isMovingBackward) 1 else 0).toByte())
        b.put((if (target.isMovingTowardsPlayer) 1 else 0).toByte())
        b.putShort(0)
        b.putDouble(target.hitbox.minX)
        b.putDouble(target.hitbox.minY)
        b.putDouble(target.hitbox.minZ)
        b.putDouble(target.hitbox.maxX)
        b.putDouble(target.hitbox.maxY)
        b.putDouble(target.hitbox.maxZ)
    } else {
        // The target block is always present, zeroed: 4+24+24+4+4+4+1+1+2+48
        b.put(ByteArray(116))
    }
    b.putInt((player.ticks and 0xFFFF_FFFFL).toInt())
    b.putInt(0)   // packet id — the stub assigns it when it registers the packet
    b.putInt(0)   // config revision
    b.putInt(0)
    require(b.position() == Wire.SLOT_SNAPSHOT) { "snapshot must be ${Wire.SLOT_SNAPSHOT} bytes, got ${b.position()}" }
    return b.array()
}

/** Velocity's entry point: the same bytes, taking the context bundle. */
fun encodeSnapshot(ctx: VelocityContext, flagsOverride: Int? = null): ByteArray =
    encodeSnapshot(ctx.player, ctx.target, ctx.originalMotion, ctx.isKnockbackHit, flagsOverride)

/**
 * Appends aim's three scalars to a snapshot.
 *
 * `sensitivity` is forwarded for the adapter's benefit only — the strategy does
 * not read it — but it rides along so a future backend that does need it is not
 * a protocol change.
 */
fun withAimTail(snapshot: ByteArray, mouseDx: Float, mouseDy: Float, sensitivity: Float): ByteArray {
    val b = Wire.le(Wire.SLOT_SNAPSHOT + Wire.AIM_TAIL)
    b.put(snapshot)
    b.putFloat(mouseDx)
    b.putFloat(mouseDy)
    b.putFloat(sensitivity)
    return b.array()
}

/** Appends click's cooldown and falling flag to a snapshot. */
fun withClickTail(snapshot: ByteArray, attackCooldown: Float, isFalling: Boolean): ByteArray {
    val b = Wire.le(Wire.SLOT_SNAPSHOT + Wire.CLICK_TAIL)
    b.put(snapshot)
    b.putFloat(attackCooldown)
    b.put((if (isFalling) 1 else 0).toByte())
    b.put(ByteArray(3))
    return b.array()
}

/**
 * A generic request: snapshot, op byte, argument count, then f64 arguments.
 *
 * Every scalar crosses as f64 and is narrowed on the far side. That is lossless
 * in both directions — f32 is a subset of f64, and every i32/bool here is far
 * below 2^53 — so one builder serves movement, tap, combat and reach.
 */
fun genericRequest(snapshot: ByteArray, op: Int, vararg args: Double): ByteArray {
    require(args.size <= 14) { "at most 14 arguments fit in one frame, got ${args.size}" }
    val b = Wire.le(Wire.SLOT_SNAPSHOT + 2 + args.size * 8)
    b.put(snapshot)
    b.put(op.toByte())
    b.put(args.size.toByte())
    for (a in args) b.putDouble(a)
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

/**
 * Op ids for the generic modules. Append-only, same reason as module ids:
 * a stub and a core are separate binaries.
 *
 * The argument list of each op is documented beside it and mirrored in
 * `core-rs/src/codec.rs`.
 */
object Ops {
    // velocity
    const val VEL_BRACE = 0         // yaw, sector → 4 bits (forward, back, left, right)
    const val VEL_DELAY_ENQUEUE = 1 // tick, chance, maxDelay, requireWeapon, flags, minD, maxD, lookAngle, gateChance → bool
    const val VEL_DELAY_PUMP = 2    // tick → released motions

    // movement
    const val MOV_SOCD = 0          // forward, back, left, right
    const val MOV_STRAFE = 1        // forward, back, left, right, distance, bearingRad, yawDeg, reach
    const val MOV_SLIDING = 2       // motionX, motionZ, yaw, releasedForward, minSpeed

    // tap
    const val TAP_CHASE = 0         // yaw, leadTicks        (positions come from the snapshot)
    const val TAP_SEVEN = 1         // lastSide, repeatChancePercent
    const val TAP_WRAP = 2          // angle
    const val TAP_TICK = 3          // nowNs
    const val TAP_POST_DELAY = 4    // nowNs, delayMs
    const val TAP_BEGIN = 5         // nowNs, durationMs
    const val TAP_RANDOM = 6        // (no args) → side
    const val TAP_WTAP_SAMPLE = 7   // kind, postDelayMin/Max, actionMin/Max, onceEveryMin/Max → packed scalar
    const val TAP_STAP_SAMPLE = 8   // same shape, own RNG stream
    const val TAP_ADTAP_SAMPLE = 9  // kind, chance, tapMin/Max, gapMin/Max → packed scalar (duration, gap, passed); own RNG stream

    // combat
    const val CBT_TRIGGER = 0       // mode, hurtResistant, maxHurt, counter, threshold, perMin, perMax, chance, hurtGate
    const val CBT_ARBITER = 1       // locked, threat, sustain, reflex, wLock, wThreat, wSustain, wReflex, threshold, maxDuty
    const val CBT_PREDICT = 2       // maxHurt, rtt, lead, predict, window, chance, alpha, seed, tick, opponentJustHit
    const val CBT_RANGE = 3         // mode, min, max, hysteresis, hasDistance, distance
    const val CBT_DECIDE = 4        // flags, minD, maxD, lookAngle, gateChance, mode, hurtGate, maxHurt, targetHurt, counter, threshold, perMin, perMax, triggerChance
    const val CBT_AUTOBLOCK_CHANCE = 5  // chance → bool (per-tick Chance gate / Lag window start roll); own RNG stream
    const val CBT_BLOCKHIT_SAMPLE = 6   // kind, postDelayMin/Max, waitMin/Max, hitPerMin/Max → packed scalar (postDelay, wait, threshold); own RNG stream

    // reach
    const val REACH_INTERSECT = 0   // minX..maxZ, originX..Z, dirX..Z, maxDist

    // player
    const val PLAYER_EAGLE_TICK = 0     // onEdge, settingsMet, sneakKeyHeld, minDelay, maxDelay → flags
    const val PLAYER_BACKTRACK_TICK = 1 // nowNs, modern, pulse, smart, minMs, maxMs, distance, legacyDistance, mustRelease → bool
    const val PLAYER_KBD_ATTACK = 2     // nowNs, direction, useLastStrafe, cooldownMs, angle, notTakingKb, velocityTick, keyLeft, keyRight → degrees
    const val PLAYER_REACH_SAMPLE = 3   // nowNs, conditionsOk, guiOpen, chance, hitPer, delayMs, hitThroughBlocks, lookingAtBlock, min, max → distance
    const val PLAYER_CLICK_ASSIST = 4   // nowNs, flags, probability, mode → extra clicks (0..2)
    const val PLAYER_HIT_TICK = 5       // nowNs, canCrit, playerHurtTime, leftDown, hasTarget, targetId, targetHurtTime, counterHit, counterCdMs → counter fires
    const val PLAYER_HIT_GATE = 6       // nowNs, guiOpen, lookingAtBlock, chance, criticals, missedSwing, hasForwardTarget, hurtResistantTime, waitFirstMs, retimeAt, pauseMs → deliver click
    const val PLAYER_EAGLE_PLACE = 7    // gatesOpen, pitchInTolerance, bridgeAssistEnabled, maxWaitTicks, cdMin, cdMax → step (0 none, 1 aim, 2 click, 3 abort)
    const val PLAYER_SPRINT_RESET = 8   // nowNs, hasTarget, inRange, gateOk, isHurt, entityId, chance, hitCount, delayMs, mode → step (0 none, 1+mode send)
    const val PLAYER_JUMP_KNOCK = 9     // gatesOpen, nowNs, chance, cooldownMode, ticksUntil, hitsUntil, delayMs → step (0 none/armed, 1 jump now)
    const val PLAYER_JUMP_PUMP = 10     // nowNs, cooldownMode, ticksUntil → step (0 none, 1 jump now)
    const val PLAYER_SUPER_KNOCKBACK = 11 // nowNs, hasTarget, triggerOk, inRange, gateOk, entityId, chance, hitCount, delayMs, mode, sprintTapAllowed, coordinationActive → packed step
    const val PLAYER_KEEPSPRINT = 12       // nowNs, tick, localSprinting, attacking, targetAlive, forward, forwardOverride → packed hold status + assert step; no RNG stream
    const val PLAYER_LAGRANGE = 13         // nowNs, guiOpen, hasDist, dist, range, maxDelayMs, sprintResetOn, isSprinting, blockSwordOn, isBlocking, holdingWeaponOn, weaponIsSword, hurtTime → step (0 none, 1 hold, 2 arm, 3 flush); no RNG stream
}

/**
 * Bit layout shared with `strategy::click_assist::flags` on the Rust side.
 *
 * Kept next to the op that carries it, not in the module: two layouts would
 * disagree about which bit means `onlyAimingEntity`, and that disagreement
 * would show up as extra clicks on presses that should have been ignored —
 * with nothing in the logs to explain it.
 */
object ClickAssistFlag {
    const val RIGHT_BUTTON = 1 shl 0
    const val GUI_OPEN = 1 shl 1
    const val USING_ITEM = 1 shl 2
    const val BLOCKING = 1 shl 3
    const val IS_WEAPON = 1 shl 4
    const val HAS_TARGET = 1 shl 5
    const val LOOKING_AT_BLOCK = 1 shl 6
    const val LEFT_ENABLED = 1 shl 7
    const val RIGHT_ENABLED = 1 shl 8
    const val WEAPONS_ONLY = 1 shl 9
    const val ONLY_AIMING_ENTITY = 1 shl 10
    const val ONLY_BLOCKS = 1 shl 11
    const val ABOVE_5_CPS = 1 shl 12
}

/** Aim's answer, decoded. */
class AimDecision(
    @JvmField val apply: Boolean,
    @JvmField val worldPoint: Vec3,
    @JvmField val hitbox: io.doppel.contract.model.Hitbox?,
    @JvmField val fractionY: Float,
    @JvmField val fractionP: Float
)

fun decodeAimDecision(f: Frame): AimDecision {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    val action = b.get().toInt()
    b.get()                     // reason
    val flags = b.short.toInt()
    val x = b.double
    val y = b.double
    val z = b.double
    b.int; b.int; b.int          // target id, clicks, tick
    val fy = b.float
    val fp = b.float
    val hasBox = b.get().toInt() != 0
    val minX = b.double; val minY = b.double; val minZ = b.double
    val maxX = b.double; val maxY = b.double; val maxZ = b.double
    return AimDecision(
        apply = action == Wire.ACTION_APPLY_ROTATION && (flags and 1) != 0,
        worldPoint = Vec3(x, y, z),
        hitbox = if (hasBox) io.doppel.contract.model.Hitbox(minX, minY, minZ, maxX, maxY, maxZ) else null,
        fractionY = fy,
        fractionP = fp
    )
}

/** Click's answer, decoded. */
fun decodeClickDecision(f: Frame): io.doppel.contract.strategy.click.ClickResult {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    val action = b.get().toInt()
    b.get()
    val flags = b.short.toInt()
    return when (action) {
        Wire.ACTION_CLICK -> io.doppel.contract.strategy.click.ClickResult.Click
        Wire.ACTION_STOP_SPRINT -> io.doppel.contract.strategy.click.ClickResult.StopSprint
        Wire.ACTION_RESTORE_SPRINT -> io.doppel.contract.strategy.click.ClickResult.RestoreSprint((flags and 1) != 0)
        else -> io.doppel.contract.strategy.click.ClickResult.Skip
    }
}

/** The four suppression bits of a movement answer, in forward/back/left/right order. */
fun decodeSuppress(f: Frame): BooleanArray {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get()
    val flags = b.short.toInt()
    return booleanArrayOf(
        flags and 1 != 0,
        flags and 2 != 0,
        flags and 4 != 0,
        flags and 8 != 0
    )
}

/** The before/after scores of a movement answer. Diagnostics only. */
fun decodeSuppressScores(f: Frame): DoubleArray {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get(); b.short
    return doubleArrayOf(b.double, b.double)
}

fun decodeScalar(f: Frame): Double =
    ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN).let {
        it.get(); it.get(); it.short
        it.double
    }

fun decodeBool(f: Frame): Boolean {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get()
    val flags = b.short.toInt()
    return flags and 1 != 0
}

/**
 * Packs [TriggerOptions]'s fourteen booleans into the bit field the core's
 * `trigger_from_packed` reads.
 *
 * The bit order is the one `write_trigger` uses for the config frames. Two layouts would
 * eventually disagree about which bit means `onlyStrafe`, and the disagreement would show
 * up as a module that fires when it should not — with nothing in the logs to explain it.
 */
fun encodeTriggerFlags(o: TriggerOptions): Double {
    var f = 0
    if (o.onlyGround) f = f or (1 shl 0)
    if (o.onlyAir) f = f or (1 shl 1)
    if (o.onlyMove) f = f or (1 shl 2)
    if (o.onlyMoveForward) f = f or (1 shl 3)
    if (o.onlyMoveBackward) f = f or (1 shl 4)
    if (o.onlyStrafe) f = f or (1 shl 5)
    if (o.onlyWhenTargetGoesBack) f = f or (1 shl 6)
    if (o.onlyWhenTargetApproaches) f = f or (1 shl 7)
    if (o.onLook) f = f or (1 shl 8)
    if (o.onlyCurrentView) f = f or (1 shl 9)
    if (o.disableOnMine) f = f or (1 shl 10)
    if (o.onlyOnClick) f = f or (1 shl 11)
    if (o.disabledInAir) f = f or (1 shl 12)
    if (o.onlySprint) f = f or (1 shl 13)
    return f.toDouble()
}

/**
 * Gate + trigger answer: fire, and the trigger's new hit counter and threshold.
 *
 * The threshold comes back because the core samples it; the JVM has no way to derive it and
 * guessing would desynchronise the next fire.
 */
class CombatDecision(@JvmField val fire: Boolean, @JvmField val counter: Int, @JvmField val threshold: Int)

fun decodeCombatDecision(f: Frame): CombatDecision {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get()
    val flags = b.short.toInt()
    b.double; b.double; b.double
    val counter = b.int
    val threshold = b.int
    return CombatDecision(flags and 1 != 0, counter, threshold)
}

/** Combat verdict: block, score, effective hurt time. */
class Verdict(
    @JvmField val block: Boolean,
    @JvmField val score: Double,
    @JvmField val effectiveHurt: Int,
    /** Layer A: the target is damage-locked, so a hit cannot land. */
    @JvmField val locked: Boolean = false,
    /** Layer B: the opponent's next attack is due within the window. */
    @JvmField val threat: Boolean = false
)

/** The motions a delay pump released this tick, in queue order. */
fun decodeMotions(f: Frame): List<Vec3> {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get()
    val n = b.short.toInt()
    val out = ArrayList<Vec3>(n)
    repeat(n) { out.add(Vec3(b.double, b.double, b.double)) }
    return out
}

fun decodeVerdict(f: Frame): Verdict {
    val b = ByteBuffer.wrap(f.payload, 0, f.payloadLen).order(ByteOrder.LITTLE_ENDIAN)
    b.get(); b.get()
    val flags = b.short.toInt()
    val score = b.double
    b.double; b.double
    val eff = b.int
    return Verdict(flags and 1 != 0, score, eff, flags and 2 != 0, flags and 4 != 0)
}

// ── config encoders ────────────────────────────────────────────────────────
//
// Field order mirrors `codec::decode_*_config` in Rust byte for byte. There is
// no schema on the wire: the only thing keeping the two in step is that both
// sides were written from the same table in IPC-PROTOCOL.md, and that a
// mismatch shows up immediately as a rejected config or a parity failure.

private fun putTrigger(b: ByteBuffer, t: io.doppel.contract.option.TriggerOptions) {
    var f = 0
    if (t.onlyGround) f = f or (1 shl 0)
    if (t.onlyAir) f = f or (1 shl 1)
    if (t.onlyMove) f = f or (1 shl 2)
    if (t.onlyMoveForward) f = f or (1 shl 3)
    if (t.onlyMoveBackward) f = f or (1 shl 4)
    if (t.onlyStrafe) f = f or (1 shl 5)
    if (t.onlyWhenTargetGoesBack) f = f or (1 shl 6)
    if (t.onlyWhenTargetApproaches) f = f or (1 shl 7)
    if (t.onLook) f = f or (1 shl 8)
    if (t.onlyCurrentView) f = f or (1 shl 9)
    if (t.disableOnMine) f = f or (1 shl 10)
    if (t.onlyOnClick) f = f or (1 shl 11)
    if (t.disabledInAir) f = f or (1 shl 12)
    if (t.onlySprint) f = f or (1 shl 13)
    b.putShort(f.toShort())
    b.putFloat(t.minDistance)
    b.putFloat(t.maxDistance)
    b.putInt(t.chance)
    b.putInt(t.delayTicks)
    b.putInt(t.delayMs)
    b.putFloat(t.lookAngleThreshold)
}

/**
 * Velocity config, 104 bytes — the layout `session::decode_config` reads.
 *
 * This encoder did not exist before: nothing ever pushed a velocity config, so the core ran every
 * mode on `VelocityConfig::default()`. The numbers the module shows in its GUI were only used by
 * the JVM-side strategies, which is why the omission was invisible until the strategies moved.
 */
fun encodeVelocityConfig(c: io.doppel.contract.strategy.velocity.VelocityConfig): ByteArray {
    val b = Wire.le(104)
    b.put(
        when (c.mode) {
            io.doppel.contract.strategy.velocity.VelocityMode.LEGIT -> 0
            io.doppel.contract.strategy.velocity.VelocityMode.CLICK -> 1
            io.doppel.contract.strategy.velocity.VelocityMode.SPRINT -> 2
        }.toByte()
    )
    b.put(if (c.onlyOnHitFrame) 1 else 0)
    b.put(if (c.whenFacingEnemyOnly) 1 else 0)
    b.put(0)                                   // require_weapon: not a Velocity option, see KnockbackDelay
    b.putFloat(c.horizontalMin)
    b.putFloat(c.horizontalMax)
    b.putFloat(c.verticalMin)
    b.putFloat(c.verticalMax)
    b.putFloat(c.clickRange)
    b.putFloat(c.maxAngleDifference)
    b.putFloat(c.sprintSector)
    b.putInt(c.reduceGivenReduced)
    b.putInt(c.reduceGivenSkipped)
    b.putInt(c.clickBurstMin)
    b.putInt(c.clickBurstMax)
    b.putInt(c.hurtTimeToClick)
    b.putInt(c.sprintHoldTicks)
    b.putInt(c.sprintGateBraced)
    b.putInt(c.sprintGateIdle)
    b.putInt(0)                                // delay_chance — DELAY lives in KnockbackDelay now
    b.putInt(0)                                // max_delay_ticks
    b.putShort(encodeTriggerFlags(c.triggerOptions).toInt().toShort())
    b.putShort(0)                              // padding, mirrors the u16 + skip(2)
    b.putFloat(c.triggerOptions.minDistance)
    b.putFloat(c.triggerOptions.maxDistance)
    b.putInt(c.triggerOptions.chance)
    b.putInt(c.triggerOptions.delayTicks)
    b.putInt(c.triggerOptions.delayMs)
    b.putFloat(c.triggerOptions.lookAngleThreshold)
    require(b.position() == 104) { "velocity config must be 104 bytes, got ${b.position()}" }
    return b.array()
}

fun encodeAimConfig(c: io.doppel.contract.strategy.aim.AimConfig): ByteArray {
    val b = Wire.le(77)
    b.put(
        when (c.mode) {
            io.doppel.contract.option.AimMode.LEGIT -> 0
            io.doppel.contract.option.AimMode.NORMAL -> 1
            io.doppel.contract.option.AimMode.SELF_ADAPTIVE -> 2
            io.doppel.contract.option.AimMode.LINEAR -> 3
        }.toByte()
    )
    b.putFloat(c.rangeMin)
    b.putFloat(c.rangeMax)
    b.putFloat(c.fov)
    b.putFloat(c.minFov)
    b.putFloat(c.aimSpeedY)
    b.putFloat(c.aimSpeedP)
    b.putInt(c.speed)
    b.putFloat(c.smoothness)
    b.putFloat(c.noiseIntensity)
    b.putFloat(c.offset)
    b.putFloat(c.multipointX)
    b.putFloat(c.multipointY)
    var f = 0
    if (c.linear) f = f or 1
    if (c.lockOnCrosshair) f = f or 2
    b.putShort(f.toShort())
    putTrigger(b, c.triggerOptions)
    require(b.position() == 77) { "aim config must be 77 bytes, got ${b.position()}" }
    return b.array()
}

fun encodeClickConfig(c: io.doppel.contract.strategy.click.ClickConfig): ByteArray {
    val b = Wire.le(38)
    b.put(0)
    b.putInt(c.minCps)
    b.putInt(c.maxCps)
    b.put(if (c.clickMode == io.doppel.contract.option.ClickMode.DOUBLE) 1 else 0)
    b.put(if (c.mode == io.doppel.contract.strategy.click.ClickOperatingMode.LEGIT) 1 else 0)
    b.put(if (c.disableOnBlock) 1 else 0)
    putTrigger(b, c.triggerOptions)
    require(b.position() == 38) { "click config must be 38 bytes, got ${b.position()}" }
    return b.array()
}

fun encodeCooldownClickConfig(c: io.doppel.contract.strategy.click.CooldownClickConfig): ByteArray {
    val b = Wire.le(35)
    b.put(1)
    b.putFloat(c.cooldownThreshold)
    b.put(
        when (c.critMode) {
            io.doppel.contract.strategy.click.CritMode.OFF -> 0
            io.doppel.contract.strategy.click.CritMode.ON -> 1
            io.doppel.contract.strategy.click.CritMode.SMART -> 2
        }.toByte()
    )
    b.put(if (c.critStopSprint) 1 else 0)
    b.put(if (c.cooldownMode == io.doppel.contract.strategy.click.CooldownClickMode.LEGIT) 1 else 0)
    b.put(if (c.disableOnBlock) 1 else 0)
    putTrigger(b, c.triggerOptions)
    require(b.position() == 35) { "cooldown click config must be 35 bytes, got ${b.position()}" }
    return b.array()
}

fun encodeTapConfig(repeatChancePercent: Int, leadTicks: Int): ByteArray {
    val b = Wire.le(8)
    b.putInt(repeatChancePercent)
    b.putInt(leadTicks)
    return b.array()
}

fun encodeCombatConfig(
    maxHurt: Int,
    rtt: Int,
    lead: Int,
    predict: Boolean,
    window: Int,
    chance: Int,
    alpha: Double,
    seedInterval: Double,
    rangeMode: Int,
    rangeMin: Float,
    rangeMax: Float,
    hysteresis: Float
): ByteArray {
    val b = Wire.le(50)
    b.putInt(maxHurt)
    b.putInt(rtt)
    b.putInt(lead)
    b.put(if (predict) 1 else 0)
    b.putInt(window)
    b.putInt(chance)
    b.putDouble(alpha)
    b.putDouble(seedInterval)
    b.put(rangeMode.toByte())
    b.putFloat(rangeMin)
    b.putFloat(rangeMax)
    b.putFloat(hysteresis)
    require(b.position() == 50) { "combat config must be 50 bytes, got ${b.position()}" }
    return b.array()
}
