package io.doppel.adapter.common.module.combat

import io.doppel.core.condition.ConditionChecker
import io.doppel.core.model.*
import io.doppel.core.strategy.velocity.ClickVelocityStrategy
import io.doppel.core.strategy.velocity.LegitVelocityStrategy
import io.doppel.core.strategy.velocity.VelocityConfig
import io.doppel.core.strategy.velocity.VelocityMode
import io.doppel.core.strategy.velocity.VelocityResult
import io.doppel.core.strategy.velocity.VelocityStrategy
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.triggerOptions
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Velocity Module — knockback reduction.
 *
 * Modes: Legit (range-random scaling), Click (click-burst), Sprint (input-side,
 * packet untouched).
 * (Delay was split out into the standalone KnockbackDelay module.)
 *
 * ## Sprint mode is a different animal from the other two
 *
 * Legit and Click rewrite the S12 velocity vector: the server computes V, the
 * client sends (1-r)·V. Grim's KnockbackHandler records the V it sent, predicts
 * the player's position from it and compares — so every rewritten hit opens with
 * a first-tick position error of r·0.4 blocks that no amount of tuning removes
 * (`behavioral-tests/velocity_decay_lab.py`).
 *
 * Sprint mode does not delegate to a [VelocityStrategy], because there is no
 * result to compute — it returns `Pass(originalMotion)` and the packet is
 * applied by vanilla untouched. It manipulates two things the server already
 * reads instead:
 *
 *  1. **Movement input.** Vanilla `knockBack()` does `motion /= 2` and *then*
 *     subtracts the knockback, so the velocity you already had is half your
 *     fate. The knockback points away from the attacker, so the cancelling
 *     direction is *toward the source*; on a hit we press the keys that point
 *     there for [sprintHoldTicks] ticks.
 *  2. **The server-side sprint mirror** (`EventBridge.serverSprintState`), which
 *     is what the server's movement prediction for us is computed from. Vanilla
 *     clears sprint on several damage paths; we re-assert it.
 *
 * Both are inputs the server *expects* a client to change, so the resulting
 * motion is the server's own computation and there is no prediction error to
 * find. Vanilla itself desyncs the two sprint states whenever a player attacks
 * while holding sprint (MC-69459, Unresolved), which is why a server cannot
 * police the asserted state without flagging every normal sprint-attacker.
 *
 * Honest bounds: this is a weaker but quieter channel than Legit, not a stronger
 * one. The cancellation is limited by your own speed against the knockback arc.
 *
 * Architecture note: Sprint is the one mode whose algorithm lives in the module
 * rather than in a [VelocityStrategy] — it is a tick-driven input action, not a
 * per-packet transformation, and there is deliberately no result object.
 */
object Velocity : Module("Velocity", Category.COMBAT), HudLineProvider {

    // ========== HUD value (read once on enable/config change) ==========
    /**
     * What the HUD row shows: the *ranges the user configured*, not a derived single number.
     *
     * It used to print the midpoint of each pair — a 0%..100% horizontal range showed as "50%",
     * which is a number the user never set and which disagrees with the two values the panel shows.
     */
    override fun hudValue(): String = when (mode) {
        "Click" -> "Click $clicksMin-$clicksMax clicks"
        "Sprint" -> "Sprint brace ${sprintHoldTicks}t @ ${sprintGateP}/${sprintGateQ}%"
        else -> "Legit ${pctRange(horizontalMin, horizontalMax)}/${pctRange(verticalMin, verticalMax)}"
    }

    /**
     * One retention range as the HUD spells it: `80%-100%`, collapsing to a single value when both
     * ends are equal (`100%`) so an unchanged axis does not read as `100%-100%`.
     */
    private fun pctRange(min: Float, max: Float): String {
        val lo = (min * 100f).roundToInt()
        val hi = (max * 100f).roundToInt()
        return if (lo == hi) "$lo%" else "${minOf(lo, hi)}%-${maxOf(lo, hi)}%"
    }

    override fun hudHighlight(): Boolean = true

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Legit", "Click", "Sprint"))

    // ========== Sprint mode (packet never touched — see the object KDoc) ==========
    /** How many ticks to keep pressing toward the knockback source after a hit. */
    private val sprintHoldTicks by int("SprintHoldTicks", 4, 1..12, "ticks")
    /** Re-assert the server-side sprint flag after a hit. */
    private val sprintSyncServerSprint by boolean("SprintSync", true)
    // The gate is two numbers, not a cadence enum. Defaults 25/25 are memoryless
    // at the human sprint-reset alignment rate; measured against the human
    // population a memoryless gate scores run-length D≈0.01 while a
    // deterministic cadence (the old EveryNth) scores D≈0.21.
    private val sprintGateP by int("SprintGateBraced", 25, 0..100, "%")
    private val sprintGateQ by int("SprintGateIdle", 25, 0..100, "%")
    /** cos of the half-angle of the press sectors: cos(67.5°) ≈ 0.38. */
    private val sprintSector by float("SprintSector", 0.38f, 0.05f..0.95f)

    // ========== Legit — horizontal (min/max separate) ==========
    private val horizontalMin by float("HorizontalMin", 0.4f, 0.0f..1.0f)
    private val horizontalMax by float("HorizontalMax", 0.6f, 0.0f..1.0f)

    // ========== Legit — vertical (min/max separate) ==========
    private val verticalMin by float("VerticalMin", 0.4f, 0.0f..1.0f)
    private val verticalMax by float("VerticalMax", 0.6f, 0.0f..1.0f)

    // ========== Probability ==========
    private val probability by int("Chance", 100, 0..100, "%")

    // ========== Legit: only reduce on the frame the player is hit (LB port) ==========
    private val onlyOnHitFrame by boolean("OnlyOnHitFrame", false)

    // ========== OnlyOnHitFrame combo naturalisation ==========
    // Extra anti-pattern layer for sustained attacks: instead of reducing on EVERY hit, skip some
    // hits randomly so the reduction doesn't form a fixed, detectable rhythm.
    //   Incremental — reduce chance grows with combo count (rare → more often as you keep getting hit).
    //   EveryNth    — reduce only every Nth hit (N randomized each cycle) — a spaced-out rhythm.
    //   Both        — EveryNth picks WHICH hits are candidates, Incremental adds randomness on top.
    // A combo is consecutive hits within [HitWindowMs]; a longer gap resets it.
    private val naturalMode by choices("NaturalMode", arrayOf("Both", "Incremental", "EveryNth", "Off"))
    private val hitWindowMs by int("HitWindowMs", 800, 100..2000, "ms")
    private val baseChance by int("BaseChance", 20, 0..100, "%")
    private val chancePerHit by int("ChancePerHit", 25, 0..100, "%")
    private val maxChance by int("MaxChance", 95, 0..100, "%")
    private val nthMin by int("NthMin", 2, 1..10)
    private val nthMax by int("NthMax", 4, 1..10)

    // ========== Condition flags (six independent toggles) ==========
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyCurrentView by boolean("OnlyCurrentView", false)
    private val disabledInAir by boolean("DisabledInAir", true)

    // ========== Unified trigger engine ==========
    private val triggerOptions by triggerOptions("Trigger") {
        onlyMove = this@Velocity.onlyMove
        onlyMoveForward = this@Velocity.onlyMoveForward
        onlyWhenTargetGoesBack = this@Velocity.onlyWhenTargetGoesBack
        onlyGround = this@Velocity.onlyGround
        onlyCurrentView = this@Velocity.onlyCurrentView
        disabledInAir = this@Velocity.disabledInAir
    }

    // ========== Click mode config ==========
    private val clicksMin by int("ClicksMin", 2, 1..10)
    private val clicksMax by int("ClicksMax", 5, 1..10)
    private val hurtTimeToClick by int("HurtTimeToClick", 8, 0..10)
    private val whenFacingEnemyOnly by boolean("WhenFacingEnemyOnly", true)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 0f..180f, "degrees")
    private val clickRange by float("ClickRange", 3.0f, 0.0f..6.0f, "blocks")

    // ========== Core Strategies (Algorithm lives here) ==========
    private val legitStrategy = LegitVelocityStrategy()
    private val clickStrategy = ClickVelocityStrategy()
    private val strategyState = VelocityStrategy.State()

    /** Named listener reference so we can unregister exactly this handler (chain-friendly). */
    private val velocityListenerRef: (VelocityContext) -> PlatformCommand = { ctx ->
        if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
    }

    // ========== Combo state (OnlyOnHitFrame naturalisation) ==========
    // Consecutive-hit counter (within HitWindowMs). Written on the Netty thread.
    @Volatile private var combo = 0
    @Volatile private var lastHitNano = 0L
    // EveryNth: how many hits remain before the next reduce candidate.
    @Volatile private var nthRemaining = 2

    // ========== Sprint mode state ==========
    /** Unit vector of the knockback SOURCE (opposite the knockback itself). */
    @Volatile private var braceSrcX = 0.0
    @Volatile private var braceSrcZ = 0.0
    @Volatile private var braceTicksLeft = 0
    @Volatile private var braceKeysHeld = false
    /** Gate state across hits: did the previous hit brace? */
    @Volatile private var braceLastPassed = false

    // ========== Config Builder ==========
    private fun buildConfig(): VelocityConfig = VelocityConfig(
        mode = when (mode) {
            "Legit" -> VelocityMode.LEGIT
            "Click" -> VelocityMode.CLICK
            "Sprint" -> VelocityMode.SPRINT
            else -> VelocityMode.LEGIT
        },
        horizontalMin = horizontalMin,
        horizontalMax = horizontalMax,
        verticalMin = verticalMin,
        verticalMax = verticalMax,
        probability = probability,
        triggerOptions = triggerOptions,
        clickBurstMin = clicksMin,
        clickBurstMax = clicksMax,
        hurtTimeToClick = hurtTimeToClick,
        whenFacingEnemyOnly = whenFacingEnemyOnly,
        maxAngleDifference = maxAngleDifference,
        clickRange = clickRange
    )

    /** Select the active strategy based on mode. */
    private fun currentStrategy(): VelocityStrategy = when (mode) {
        "Legit" -> legitStrategy
        "Click" -> clickStrategy
        else -> legitStrategy
    }

    // ========== Entry Point ==========

    /** Throttle for the velocity result diagnostic. */
    @Volatile private var velDiagCount = 0

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        // Sprint mode never rewrites the packet — it only observes the hit and arms an
        // input-side response (see the object KDoc). Returning Pass here is the whole
        // point: vanilla applies the knockback itself, so there is nothing to detect.
        if (mode == "Sprint") return onSprintKnockback(ctx)

        val config = cachedConfig { buildConfig() }
        // OnlyOnHitFrame — LiquidBounce's VelocityLegit semantics, expressed with OUR architecture:
        // reduce velocity only on the hit frame (i.e. when knocked by an actual attack, the S12
        // packet), and pass explosions (S27) through untouched. We do NOT try to read
        // hurtResistantTime (LB is a mod running on MC's main tick where that field is readable;
        // we are an injector reading from the Netty thread where it is stale/0) — the S12 packet
        // arriving IS the hit-frame signal, which is the logical equivalent without the read.
        if (onlyOnHitFrame && mode == "Legit") {
            // Explosions are not "being hit" — pass through.
            if (!ctx.isKnockbackHit) return PlatformCommand.Pass(ctx.originalMotion)
            // Combo naturalisation: under sustained attacks, skip some hits so the reduction
            // doesn't form a fixed, detectable rhythm. "Off" = reduce every hit (plain LB).
            if (!comboGatePasses()) return PlatformCommand.Pass(ctx.originalMotion)
        }
        return reduceNormally(config, ctx)
    }

    // ========== Sprint mode ==========

    /**
     * A knockback arrived. Decide whether to brace, arm the direction, and hand the
     * packet straight back to vanilla.
     */
    private fun onSprintKnockback(ctx: VelocityContext): PlatformCommand {
        val player = ctx.player
        if (!ConditionChecker.check(triggerOptions, player, ctx.target)) {
            return PlatformCommand.Pass(ctx.originalMotion)
        }
        if (!sprintGatePasses()) {
            return PlatformCommand.Pass(ctx.originalMotion)
        }
        val m = ctx.originalMotion
        val hSpeed = sqrt(m.x * m.x + m.z * m.z)
        // A pure-vertical knockback has no source direction to brace against.
        if (hSpeed < 1e-4) return PlatformCommand.Pass(ctx.originalMotion)

        braceSrcX = -m.x / hSpeed
        braceSrcZ = -m.z / hSpeed
        braceTicksLeft = sprintHoldTicks
        markWorked()
        return PlatformCommand.Pass(ctx.originalMotion)
    }

    /**
     * Two-state gate: `SprintGateBraced` is P(brace | braced last hit), `SprintGateIdle`
     * is P(brace | did not). Equal values make it memoryless — the shape the human
     * sprint-reset population has. This replaces `NaturalMode`'s EveryNth/Incremental
     * cadences, which are deterministic and therefore exactly what the run-length test
     * catches (measured: iid D≈0.01, EveryNth D=0.21).
     */
    private fun sprintGatePasses(): Boolean {
        val p = if (braceLastPassed) sprintGateP else sprintGateQ
        val pass = Random.nextInt(100) < p
        braceLastPassed = pass
        return pass
    }

    /** One tick of the brace: press the movement keys that point at the knockback source. */
    private fun sprintTick(player: PlayerState) {
        if (braceTicksLeft <= 0) {
            releaseBraceKeys()
            return
        }
        braceTicksLeft--

        val yaw = Math.toRadians(player.rotation.yaw.toDouble())
        val fwdX = -sin(yaw)
        val fwdZ = cos(yaw)
        // Right of forward in the (x, z) plane: (x, z) -> (-z, x).
        val rgtX = -fwdZ
        val rgtZ = fwdX
        val fDot = fwdX * braceSrcX + fwdZ * braceSrcZ
        val rDot = rgtX * braceSrcX + rgtZ * braceSrcZ
        val sector = sprintSector.toDouble()
        // ADDITIVE claims, not direct writes: WTap/STap OWN W/S while a tap is live and beat us on
        // a shared tick (a trade), after which we resume. Writing the fields directly was what let
        // the tap and the brace silently cancel each other.
        EventBridge.claimForward(name, fDot > sector)
        EventBridge.claimBack(name, fDot < -sector)
        EventBridge.claimRight(name, rDot > sector)
        EventBridge.claimLeft(name, rDot < -sector)
        braceKeysHeld = true

        if (sprintSyncServerSprint) {
            // ADDITIVE claim, asserted only when the game has actually cleared sprint — the same
            // condition the direct call used. Released when the brace window ends.
            if (!player.isSprinting) {
                EventBridge.claimSprint(name, true)
            }
            if (!EventBridge.serverSprintState) {
                EventBridge.sendEntityAction("START_SPRINTING")
            }
        }
    }

    private fun releaseBraceKeys() {
        // Released before the early return: SprintSync can be on while Mode = SprintSync (no keys).
        EventBridge.releaseSprint(name)
        if (!braceKeysHeld) return
        EventBridge.releaseForward(name)
        EventBridge.releaseBack(name)
        EventBridge.releaseLeft(name)
        EventBridge.releaseRight(name)
        braceKeysHeld = false
    }

    // ========== OnlyOnHitFrame combo gate ==========
    // Maintains the consecutive-hit counter and returns whether THIS hit should be reduced,
    // per the configured NaturalMode. Runs on the Netty thread (single writer for the combo
    // state — no race).
    private fun comboGatePasses(): Boolean {
        val now = System.nanoTime()
        // A gap longer than the window breaks the combo and resets the EveryNth rhythm.
        if (lastHitNano != 0L && now - lastHitNano > hitWindowMs * 1_000_000L) {
            combo = 0
            nthRemaining = Random.nextInt(nthMin, nthMax + 1).coerceAtLeast(1)
        }
        lastHitNano = now
        combo = combo + 1

        return when (naturalMode) {
            "Off" -> true
            "Incremental" -> rollIncremental()
            "EveryNth" -> rollEveryNth()
            else -> {
                // Both: EveryNth picks the candidate, Incremental adds randomness on top.
                val everyNthOk = rollEveryNth()
                everyNthOk && rollIncremental()
            }
        }
    }

    // Reduce chance grows with combo: base + (combo-1)*perHit, capped at MaxChance.
    private fun rollIncremental(): Boolean {
        val raw = baseChance + (combo - 1) * chancePerHit
        val chance = if (raw > maxChance) maxChance else raw
        val roll = Random.nextInt(100)
        return roll < chance
    }

    // Reduce only every Nth hit; N randomized to nthMin..nthMax each cycle (spaced rhythm).
    private fun rollEveryNth(): Boolean {
        if (nthRemaining > 0) {
            nthRemaining = nthRemaining - 1
            return false
        }
        nthRemaining = Random.nextInt(nthMin, nthMax + 1).coerceAtLeast(1)
        return true
    }

    /** Run the strategy (conditions + scaling) and return its command. */
    private fun reduceNormally(config: VelocityConfig, ctx: VelocityContext): PlatformCommand {
        val result = currentStrategy().execute(config, strategyState, ctx)
        // Expose whether velocity was actually modified/cancelled (for the VelocityDisplay HUD).
        val modified = result is VelocityResult.Modify || result is VelocityResult.Cancel
        EventBridge.velocityModified = modified
        // Record knockback coefficient (original vs post-reduction horizontal speed) for the
        // VelocityDisplay / KnockbackDisplay / JumpTiming HUDs.
        val origSpeed = Math.sqrt(ctx.originalMotion.x * ctx.originalMotion.x + ctx.originalMotion.z * ctx.originalMotion.z)
        val modSpeed = when (result) {
            is VelocityResult.Modify -> {
                val m = result.motion
                Math.sqrt(m.x * m.x + m.z * m.z)
            }
            is VelocityResult.Cancel -> 0.0
            else -> origSpeed // Pass/NoOp → unchanged
        }
        EventBridge.recordKnockback(ctx.originalMotion, modSpeed)
        // Diagnostic: confirm the strategy actually produced Modify (HUD color check).
        if (++velDiagCount % 10 == 0) {
            io.doppel.core.logging.CoreLogger.info(
                "[Velocity] packet result=${result.javaClass.simpleName} modified=$modified " +
                "mode=$mode hMin=$horizontalMin hMax=$horizontalMax vMin=$verticalMin vMax=$verticalMax " +
                "chance=$probability onlyOnHit=$onlyOnHitFrame"
            )
        }
        return mapResultToCommand(result)
    }

    // ========== Result Mapping ==========

    private fun mapResultToCommand(result: VelocityResult): PlatformCommand = when (result) {
        is VelocityResult.Modify -> { markWorked(); PlatformCommand.ModifyMotion(result.motion) }
        is VelocityResult.Cancel -> PlatformCommand.CancelPacket(result.packetHandle)
        is VelocityResult.ClickBurst -> PlatformCommand.ClickBurst(result.targetEntityId, result.times)
        is VelocityResult.Pass -> PlatformCommand.Pass(result.originalMotion)
        is VelocityResult.NoOp -> PlatformCommand.NoOp
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        // Reset OnlyOnHitFrame combo state.
        combo = 0
        lastHitNano = 0L
        nthRemaining = Random.nextInt(nthMin, nthMax + 1).coerceAtLeast(1)
        // Reset Sprint-mode brace state.
        braceTicksLeft = 0
        braceKeysHeld = false
        braceLastPassed = false
        EventBridge.registerTickListener { currentTick ->
            if (enabled) onTick(currentTick)
        }
        EventBridge.registerTickListener(sprintTickListenerRef)
        EventBridge.registerVelocityListener(velocityListenerRef, EventBridge.VELOCITY_PRIORITY_REDUCE)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(velocityListenerRef)
        EventBridge.unregisterTickListener(this::onTick)
        EventBridge.unregisterTickListener(sprintTickListenerRef)
        strategyState.reset()
        releaseBraceKeys()
        braceTicksLeft = 0
    }
    /**
     * Called on every tick to drain any delayed velocity packets from the
     * strategy's delay queue (kept for the strategy interface; DELAY mode moved
     * to the standalone KnockbackDelay module), and — in Sprint mode — to run
     * one tick of the brace.
     */
    fun onTick(currentTick: Int) {
        val config = cachedConfig { buildConfig() }
        val strategy = currentStrategy()
        val results = strategy.pumpDelayed(config, strategyState, currentTick)
        for (result in results) {
            val command = mapResultToCommand(result)
            if (command is PlatformCommand.ModifyMotion) {
                EventBridge.applyMotion(command.motion)
            }
        }
    }

    /**
     * Separate tick listener for Sprint mode: it needs the [PlayerState] (for the yaw
     * the brace direction is resolved against), which the `(Int) -> Unit` overload of
     * `registerTickListener` does not carry.
     */
    private val sprintTickListenerRef: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled && mode == "Sprint") sprintTick(p)
    }
}
