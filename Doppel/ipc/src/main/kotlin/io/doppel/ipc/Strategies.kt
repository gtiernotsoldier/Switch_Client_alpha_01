package io.doppel.ipc

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.option.TriggerOptions
import io.doppel.contract.strategy.aim.AimConfig
import io.doppel.contract.strategy.aim.AimResult
import io.doppel.contract.strategy.aim.AimStrategy
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.ClickStrategy
import io.doppel.contract.strategy.click.CooldownClickConfig
import io.doppel.contract.strategy.click.CritState

/**
 * Process-wide handle on the core session.
 *
 * The adapter asks [Strategies] for a strategy and never touches this directly.
 * Whoever owns the process lifecycle (the supervisor) assigns [stub] once the
 * socket is up, and clears it on shutdown — a null stub is the normal state on a
 * machine without the core binary, not an error.
 */
object CoreLink {
    @Volatile
    var stub: CoreStub? = null
}

/**
 * The single place the adapter turns "I need a strategy" into an object.
 *
 * ## Why a factory instead of a constructor
 *
 * `AimAssist` used to say `LegitAimStrategy()`. That one line is what keeps the
 * algorithm classes inside `agent.jar` and is also what would turn into
 * `NoClassDefFoundError` the moment they stop being packaged. Indirection here
 * means the choosing happens in one file instead of twenty-four.
 *
 * ## What happens when the core is not running
 *
 * The off-line implementations below do nothing, and doing nothing is the right
 * answer: an aim assist that keeps applying last tick's rotation while the
 * decision source is gone drifts the camera off target, and a clicker that
 * keeps its last verdict keeps clicking. Both are worse than visibly idle, and
 * both are unrecoverable once the core comes back. `PROTECTION.md` calls this
 * trade explicitly: the jar ships no algorithm, so no core means no module.
 */
object Strategies {

    /**
     * Frame correlation id.
     *
     * The core only echoes it back so a late reply can be matched to (and
     * discarded instead of) the request that is waiting. Nothing on the JVM side
     * reads the tick for logic, so a plain counter is enough — it does not have
     * to agree with the game's tick counter.
     */
    private val sequence = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nextTick(): Int = sequence.incrementAndGet()

    fun aim(): AimStrategy {
        val stub = CoreLink.stub ?: return OfflineAim
        return RemoteAimStrategy(stub) { nextTick() }
    }

    fun click(): ClickStrategy<ClickConfig, ClickStrategy.State> {
        val stub = CoreLink.stub ?: return OfflineClick
        return RemoteClickStrategy(stub) { nextTick() }
    }

    /**
     * Concrete type on purpose: the 1.9+ adapter also calls `processTick`, which the
     * interface does not declare. Passing a null stub makes it inert.
     */
    fun cooldownClick(): RemoteCooldownClickStrategy =
        RemoteCooldownClickStrategy(CoreLink.stub) { nextTick() }

    /** True when a core session is attached; the HUD uses it to say so. */
    val online: Boolean get() = CoreLink.stub != null

    /**
     * Instant Stop: is the player still sliding in the direction they just released?
     *
     * Off-line this answers `false`, and `false` means "do not press the opposite key". That is the
     * safe direction to fail in: the module stays idle instead of holding a key down on a guess.
     */
    fun sliding(
        player: PlayerState,
        motionX: Double,
        motionZ: Double,
        yaw: Float,
        releasedForward: Boolean,
        minSpeed: Double
    ): Boolean {
        val stub = CoreLink.stub ?: return false
        return stub.movementSliding(
            player, null, motionX, motionZ, yaw, releasedForward, minSpeed, nextTick()
        ) ?: false
    }

    /**
     * The condition gate alone: may this module act on this tick?
     *
     * Off-line answers `false`. Every caller treats false as "do not act", so a machine without
     * the core gets modules that are visibly idle rather than modules acting on a stale or
     * unconditional verdict.
     */
    fun gate(
        player: PlayerState,
        target: TargetState?,
        options: TriggerOptions
    ): Boolean {
        val stub = CoreLink.stub ?: return false
        return stub.combatDecide(
            player, target, options,
            mode = -1, hurtGate = false, maxHurtTime = 0, targetHurtResistantTime = 0,
            hitCounter = 0, hitThreshold = 1, hitPerMin = 1, hitPerMax = 1, triggerChance = 100,
            tick = nextTick()
        )?.fire ?: false
    }

    /**
     * Gate + combat trigger in one round trip.
     *
     * Returns null when no core is attached; callers then leave their counters untouched and do
     * not fire. [CombatDecision.counter] and [CombatDecision.threshold] must be written back even when the
     * answer is "do not fire" — the core advances the counter on every evaluated attack, and
     * dropping that increment makes the module count hits it never made.
     */
    fun decide(
        player: PlayerState,
        target: TargetState?,
        options: TriggerOptions,
        mode: Int,
        hurtGate: Boolean,
        maxHurtTime: Int,
        targetHurtResistantTime: Int,
        hitCounter: Int,
        hitThreshold: Int,
        hitPerMin: Int,
        hitPerMax: Int,
        triggerChance: Int
    ): CombatDecision? {
        val stub = CoreLink.stub ?: return null
        return stub.combatDecide(
            player, target, options, mode, hurtGate, maxHurtTime, targetHurtResistantTime,
            hitCounter, hitThreshold, hitPerMin, hitPerMax, triggerChance, nextTick()
        )
    }

    /**
     * Strafe Assist: which movement keys to damp, as `[forward, back, left, right]`.
     *
     * Off-line returns null — the caller then leaves the player's own WASD untouched, which is the
     * same thing an all-false array would do, but without allocating one per tick to say it.
     */
    fun strafe(
        player: PlayerState,
        target: TargetState?,
        forward: Boolean,
        back: Boolean,
        left: Boolean,
        right: Boolean,
        distance: Double,
        bearingRad: Double,
        yawDeg: Double,
        reach: Double
    ): BooleanArray? {
        val stub = CoreLink.stub ?: return null
        return stub.movementStrafe(
            player, target, forward, back, left, right,
            distance, bearingRad, yawDeg, reach, nextTick()
        )
    }

    /**
     * SOCD: which of the four movement keys should be taken away, as `[f, b, l, r]`.
     *
     * Off-line returns null, and null means "suppress nothing" — the player's own two keys
     * then cancel each other exactly as vanilla does. That is a working movement, just without
     * the snap; the alternative (defaulting to true) would remove keys the user is holding.
     */
    fun socd(
        player: PlayerState,
        target: TargetState?,
        forward: Boolean,
        back: Boolean,
        left: Boolean,
        right: Boolean
    ): BooleanArray? {
        val stub = CoreLink.stub ?: return null
        return stub.movementSocd(player, target, forward, back, left, right, nextTick())
    }

    /** One tap tick: the event to handle and the phase it left behind. */
    class TapTick(val event: Int, val phase: Int) {
        companion object {
            const val NONE = 0
            const val END_TAP = 1
            const val SHOULD_START_TAP = 2

            const val IDLE = 0
            const val POST_DELAY = 1
            const val TAPPING = 2

            /** No core: stay idle forever, which is the state every tap module starts in. */
            val OFFLINE = TapTick(NONE, IDLE)
        }
    }

    fun tapTick(player: PlayerState, target: TargetState?, nowNs: Long): TapTick {
        val stub = CoreLink.stub ?: return TapTick.OFFLINE
        val v = stub.tapTick(player, target, nowNs, nextTick()) ?: return TapTick.OFFLINE
        val n = v.toInt()
        return TapTick(n and 3, n shr 2)
    }

    fun tapBegin(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        durationMs: Int,
        postDelay: Boolean
    ) {
        val stub = CoreLink.stub ?: return
        stub.tapBegin(player, target, nowNs, durationMs, postDelay, nextTick())
    }

    /** Tap side from target motion, or null when there is no target (or no core). */
    fun tapChase(player: PlayerState, target: TargetState?, yaw: Float, leadTicks: Int): Int? {
        val stub = CoreLink.stub ?: return null
        val v = stub.tapChase(player, target, yaw, leadTicks, nextTick()) ?: return null
        val side = v.toInt()
        return if (side < 0) null else side
    }

    fun tapSeven(player: PlayerState, target: TargetState?, lastSide: Int, repeatChance: Int): Int? {
        val stub = CoreLink.stub ?: return null
        val v = stub.tapSeven(player, target, lastSide, repeatChance, nextTick()) ?: return null
        val side = v.toInt()
        return if (side < 0) null else side
    }

    /** Uniform side draw from the core's tap stream; null when there is no core. */
    fun tapRandom(player: PlayerState, target: TargetState?): Int? {
        val stub = CoreLink.stub ?: return null
        val v = stub.tapRandom(player, target, nextTick()) ?: return null
        val side = v.toInt()
        return if (side < 0) null else side
    }

    fun tapWrap(player: PlayerState, target: TargetState?, angle: Float): Float? {
        val stub = CoreLink.stub ?: return null
        return stub.tapWrap(player, target, angle, nextTick())?.toFloat()
    }

    /** Forget what the core remembers about one module. Safe to call with no core attached. */
    fun reset(module: Int) {
        CoreLink.stub?.reset(module)
    }

    fun resetMovement() = reset(Wire.MODULE_MOVEMENT)
    fun resetTap() = reset(Wire.MODULE_TAP)
    fun resetCombat() = reset(Wire.MODULE_COMBAT)
    fun resetVelocity() = reset(Wire.MODULE_VELOCITY)
    fun resetPlayer() = reset(Wire.MODULE_PLAYER)

    /**
     * AutoBlock: is the target inside the gate's distance band?
     *
     * The `OnEnter` latch is core state, which is why this cannot be a local comparison: the
     * answer depends on whether the target was inside on the previous tick.
     *
     * Off-line answers `false` — do not block. Same reasoning as everywhere else: a module whose
     * question cannot be answered stays idle instead of acting on a guess.
     */
    fun range(
        player: PlayerState,
        target: TargetState?,
        onEnter: Boolean,
        minDistance: Float,
        maxDistance: Float,
        hysteresis: Float,
        distance: Float?
    ): Boolean {
        val stub = CoreLink.stub ?: return false
        return stub.combatRange(
            player, target, if (onEnter) 1 else 0, minDistance, maxDistance, hysteresis,
            distance, nextTick()
        ) ?: false
    }

    /**
     * AutoBlock Predict: hold the guard or swing, from the target's damage lock plus the learned
     * attack rhythm. The rhythm estimate (`intervalEma`) lives in the core and is updated by this
     * call, so calling it twice per tick would learn twice as fast.
     */
    fun predict(
        player: PlayerState,
        target: TargetState?,
        maxHurt: Int,
        rttTicks: Int,
        leadTicks: Int,
        predictAttacks: Boolean,
        windowTicks: Int,
        chance: Int,
        currentTick: Int,
        opponentJustHit: Boolean
    ): Verdict? {
        val stub = CoreLink.stub ?: return null
        return stub.combatPredict(
            player, target, maxHurt, rttTicks, leadTicks, predictAttacks, windowTicks, chance,
            INTERVAL_ALPHA, SEED_INTERVAL_TICKS, currentTick, opponentJustHit, nextTick()
        )
    }

    /**
     * AutoBlock Adaptive: weigh the four signals and apply the duty-cycle budget.
     *
     * The duty window is core state as well — `record` happens inside this call, so the budget
     * counts one tick per request, not per decision the module happens to make.
     */
    fun arbiter(
        player: PlayerState,
        target: TargetState?,
        locked: Boolean,
        threat: Boolean,
        sustain: Boolean,
        reflex: Boolean,
        wLock: Double,
        wThreat: Double,
        wSustain: Double,
        wReflex: Double,
        threshold: Double,
        maxDuty: Double
    ): Verdict? {
        val stub = CoreLink.stub ?: return null
        return stub.combatArbiter(
            player, target, locked, threat, sustain, reflex,
            wLock, wThreat, wSustain, wReflex, threshold, maxDuty, nextTick()
        )
    }

    /**
     * Smoothing for the opponent-attack estimate. Fixed rather than configurable: it is the
     * estimator's own constant, and exposing it would let a slider silently change how fast the
     * predictor learns — a different algorithm, not a tuning value.
     */
    private const val INTERVAL_ALPHA = 0.35
    private const val SEED_INTERVAL_TICKS = 12.0

    /** One velocity hit's answer, plus the one thing the result type cannot express. */
    class VelocityOutcome(
        @JvmField val result: io.doppel.contract.strategy.velocity.VelocityResult,
        /** SPRINT only: this hit opened a brace window. */
        @JvmField val sprintArmed: Boolean
    )

    /**
     * Velocity, one incoming knockback.
     *
     * The config is pushed before every hit rather than cached. A knockback arrives at most a few
     * times a second, and a config frame is 104 bytes — the alternative is tracking which options
     * changed, and getting that wrong means the core runs the previous mode while the GUI shows
     * the new one, silently.
     *
     * Off-line passes the packet through: vanilla applies the knockback itself, which is exactly
     * what "no reduction configured" looks like.
     */
    fun velocity(
        ctx: io.doppel.contract.model.VelocityContext,
        config: io.doppel.contract.strategy.velocity.VelocityConfig
    ): VelocityOutcome {
        val stub = CoreLink.stub
            ?: return VelocityOutcome(io.doppel.contract.strategy.velocity.VelocityResult.Pass(ctx.originalMotion), false)
        stub.pushConfig(Wire.MODULE_VELOCITY, encodeVelocityConfig(config))
        val d = stub.decideFrame(ctx, nextTick())
            ?: return VelocityOutcome(io.doppel.contract.strategy.velocity.VelocityResult.Pass(ctx.originalMotion), false)
        val result = when (d.action) {
            Wire.ACTION_PASS -> io.doppel.contract.strategy.velocity.VelocityResult.Pass(ctx.originalMotion)
            Wire.ACTION_MODIFY -> io.doppel.contract.strategy.velocity.VelocityResult.Modify(d.motion)
            Wire.ACTION_CLICK_BURST -> io.doppel.contract.strategy.velocity.VelocityResult.ClickBurst(d.targetId, d.clicks)
            Wire.ACTION_CANCEL -> io.doppel.contract.strategy.velocity.VelocityResult.Cancel(ctx.packetHandle)
            else -> io.doppel.contract.strategy.velocity.VelocityResult.NoOp
        }
        return VelocityOutcome(result, d.reason == Wire.REASON_SPRINT_ARMED)
    }

    /**
     * One tick of the sprint brace: `[forward, back, left, right]`, or null when the window is
     * closed (or there is no core). Null means release every key.
     */
    fun braceKeys(player: PlayerState, target: TargetState?, yaw: Float, sector: Double): BooleanArray? {
        val stub = CoreLink.stub ?: return null
        return stub.velocityBrace(player, target, yaw, sector, nextTick())
    }

    /**
     * KnockbackDelay: hold this knockback back?
     *
     * Off-line answers false — the packet goes through with vanilla's trajectory, which is what a
     * user who did not enable the module would see, and the safe direction: holding a packet the
     * client then forgets to release leaves the player stuck in the air.
     */
    fun delayEnqueue(
        ctx: io.doppel.contract.model.VelocityContext,
        nowTick: Int,
        chance: Int,
        maxDelayTicks: Int,
        requireWeapon: Boolean,
        options: TriggerOptions
    ): Boolean {
        val stub = CoreLink.stub ?: return false
        return stub.velocityDelayEnqueue(
            ctx, nowTick, chance, maxDelayTicks, requireWeapon, options, nextTick()
        ) ?: false
    }

    /** KnockbackDelay: the held motions that came due this tick. Empty when none (or no core). */
    fun delayPump(player: PlayerState, target: TargetState?, nowTick: Int): List<io.doppel.contract.util.Vec3> {
        val stub = CoreLink.stub ?: return emptyList()
        return stub.velocityDelayPump(player, target, nowTick, nextTick()) ?: emptyList()
    }

    /**
     * Eagle: one tick of the sneak machine.
     *
     * `null` means the core did not answer. The caller must then write **no**
     * key state at all — pressing would leave a sneak nobody owns, releasing
     * would cancel the player's own.
     */
    fun eagleTick(
        player: PlayerState,
        target: TargetState?,
        onEdge: Boolean,
        settingsMet: Boolean,
        sneakKeyHeld: Boolean,
        minDelay: Int,
        maxDelay: Int
    ): EagleStep? {
        val stub = CoreLink.stub ?: return null
        val flags = stub.playerEagle(
            player, target, onEdge, settingsMet, sneakKeyHeld, minDelay, maxDelay, nextTick()
        ) ?: return null
        return EagleStep(
            press = flags and 1 != 0,
            release = flags and 2 != 0,
            autoSneakActive = flags and 4 != 0
        )
    }

    /**
     * Eagle AutoPlace: one tick of the aim-first placement machine.
     *
     * The module supplies the two judgements it made (the gate and whether the
     * view is inside the tolerance) plus the cooldown bounds; the core owns the
     * aim state and the random cooldown. `null` means no answer — the module
     * then writes neither a rotation nor a right-click.
     */
    fun eaglePlace(
        player: PlayerState,
        target: TargetState?,
        gatesOpen: Boolean,
        pitchInTolerance: Boolean,
        bridgeAssistEnabled: Boolean,
        maxWaitTicks: Int,
        cooldownMin: Int,
        cooldownMax: Int
    ): EaglePlaceStep? {
        val stub = CoreLink.stub ?: return null
        val code = stub.playerEaglePlace(
            player, target, gatesOpen, pitchInTolerance, bridgeAssistEnabled,
            maxWaitTicks, cooldownMin, cooldownMax, nextTick()
        ) ?: return null
        return EaglePlaceStep.fromCode(code)
    }

    /**
     * Backtrack: hold position updates this tick?
     *
     * `null` means the core did not answer, and the caller must let the updates
     * through. Holding is the state that can desync the view; not holding is
     * merely vanilla.
     */
    fun backtrackTick(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        modern: Boolean,
        pulse: Boolean,
        smart: Boolean,
        minDelayMs: Int,
        maxDelayMs: Int,
        distance: Float,
        legacyDistance: Float,
        mustRelease: Boolean
    ): Boolean? {
        val stub = CoreLink.stub ?: return null
        return stub.playerBacktrack(
            player, target, nowNs, modern, pulse, smart,
            minDelayMs, maxDelayMs, distance, legacyDistance, mustRelease, nextTick()
        )
    }

    /**
     * KnockbackDisplace: how far to flick the yaw on a landed hit, in degrees.
     *
     * `null` means the core did not answer; the caller must then skip the flick
     * rather than guess an angle.
     */
    fun kbdAttack(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        direction: Int,
        useLastStrafe: Boolean,
        cooldownMs: Int,
        angle: Float,
        notTakingKnockback: Boolean,
        velocityThisTick: Boolean,
        keyLeft: Boolean,
        keyRight: Boolean
    ): Float? {
        val stub = CoreLink.stub ?: return null
        return stub.playerKbdAttack(
            player, target, nowNs, direction, useLastStrafe, cooldownMs, angle,
            notTakingKnockback, velocityThisTick, keyLeft, keyRight, nextTick()
        )
    }

    /**
     * Reach: sample one press.
     *
     * `null` means the core did not answer; the caller must leave the press
     * alone. Extending reach without being told to is the one thing this module
     * must never do by accident.
     */
    fun reachSample(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        conditionsOk: Boolean,
        guiOpen: Boolean,
        chance: Int,
        hitPer: Int,
        delayMs: Int,
        hitThroughBlocks: Boolean,
        lookingAtBlock: Boolean,
        reachMin: Float,
        reachMax: Float
    ): Double? {
        val stub = CoreLink.stub ?: return null
        return stub.playerReachSample(
            player, target, nowNs, conditionsOk, guiOpen, chance, hitPer, delayMs,
            hitThroughBlocks, lookingAtBlock, reachMin, reachMax, nextTick()
        )
    }

    /**
     * ClickAssist: how many extra clicks a physical press earns (0, 1 or 2).
     *
     * `null` means the core did not answer; the caller must add nothing.
     * Guessing here would fire a real OS mouse event on a press the core never
     * approved.
     */
    fun clickAssist(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        flags: Int,
        probability: Int,
        mode: Int
    ): Int? {
        val stub = CoreLink.stub ?: return null
        return stub.playerClickAssist(
            player, target, nowNs, flags, probability, mode, nextTick()
        )?.toInt()
    }

    /**
     * HitSelect, tick half. `null` means the core did not answer; the adapter
     * must then simply not fire a counter-attack this tick.
     */
    fun hitTick(
        player: PlayerState,
        target: TargetState?,
        nowNs: Long,
        canCrit: Boolean,
        playerHurtTime: Int,
        leftMouseDown: Boolean,
        hasTarget: Boolean,
        targetId: Int,
        targetHurtTime: Int,
        counterHit: Boolean,
        counterCdMs: Int
    ): Boolean? {
        val stub = CoreLink.stub ?: return null
        return stub.playerHitTick(
            player, target, nowNs, canCrit, playerHurtTime, leftMouseDown,
            hasTarget, targetId, targetHurtTime, counterHit, counterCdMs, nextTick()
        )
    }

    /**
     * HitSelect, gate half.
     *
     * `null` means the core did not answer; the caller must **deliver** the
     * click. Swallowing a click without being told to is the one thing this
     * module must never do by accident — the safe direction here is the
     * opposite of Reach's.
     */
    fun hitGate(
        player: PlayerState,
        target: TargetState?,
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
        pauseMs: Int
    ): Boolean? {
        val stub = CoreLink.stub ?: return null
        return stub.playerHitGate(
            player, target, nowNs, guiOpen, lookingAtBlock, chance, criticals,
            missedSwing, hasForwardTarget, forwardHurtResistantTime,
            waitFirstMs, retimeAt, pauseMs, nextTick()
        )
    }
}

/** What the core decided for one Eagle tick. */
class EagleStep(
    @JvmField val press: Boolean,
    @JvmField val release: Boolean,
    @JvmField val autoSneakActive: Boolean
)

/** What the core decided for one Eagle AutoPlace tick. */
enum class EaglePlaceStep(val code: Int) {
    NONE(0), AIM(1), CLICK(2), ABORT(3);

    companion object {
        /** Unknown codes degrade to NONE: never invent a placement step. */
        @JvmStatic
        fun fromCode(code: Int): EaglePlaceStep? = entries.firstOrNull { it.code == code }
    }
}

private object OfflineAim : AimStrategy {
    override val name: String get() = "OfflineAim"
    override fun execute(config: AimConfig, state: AimStrategy.State, input: Any) = AimResult.Skip
}

private object OfflineClick : ClickStrategy<ClickConfig, ClickStrategy.State> {
    override val name: String get() = "OfflineClick"
    override fun execute(config: ClickConfig, state: ClickStrategy.State, input: Any) = ClickResult.Skip
}

