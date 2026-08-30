package io.doppel.adapter.common.module.combat

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
import kotlin.random.Random

/**
 * Velocity Module — knockback reduction.
 *
 * Modes: Legit (range-random scaling), Click (click-burst).
 * (Delay was split out into the standalone KnockbackDelay module.)
 *
 * Architecture compliance (Sandwich):
 * 1. Logic in module: config assembly, result-to-PlatformCommand mapping.
 * 2. Algorithm in Core: all condition checking, scaling, delay queue,
 *    and click-burst logic lives in [VelocityStrategy] implementations.
 */
object Velocity : Module("Velocity", Category.COMBAT), HudLineProvider {

    // ========== HUD value (read once on enable/config change) ==========
    override fun hudValue(): String {
        val hPct = ((horizontalMin + horizontalMax) / 2f * 100f).toInt()
        val vPct = ((verticalMin + verticalMax) / 2f * 100f).toInt()
        return "$mode ${hPct}/${vPct}%"
    }
    override fun hudHighlight(): Boolean = true

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Legit", "Click"))

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

    // ========== Config Builder ==========
    private fun buildConfig(): VelocityConfig = VelocityConfig(
        mode = when (mode) {
            "Legit" -> VelocityMode.LEGIT
            "Click" -> VelocityMode.CLICK
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

    // OnlyOnHitFrame combo gate. Maintains the consecutive-hit counter and returns whether THIS
    // hit should be reduced, per the configured NaturalMode. Runs on the Netty thread (single
    // writer for the combo state — no race).
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
        is VelocityResult.Modify -> PlatformCommand.ModifyMotion(result.motion)
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
        EventBridge.registerTickListener { currentTick ->
            if (enabled) onTick(currentTick)
        }
        EventBridge.registerVelocityListener(velocityListenerRef, EventBridge.VELOCITY_PRIORITY_REDUCE)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(velocityListenerRef)
        EventBridge.unregisterTickListener(this::onTick)
        strategyState.reset()
    }
    /**
     * Called on every tick to drain any delayed velocity packets from the
     * strategy's delay queue (kept for the strategy interface; DELAY mode moved
     * to the standalone KnockbackDelay module).
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
}
