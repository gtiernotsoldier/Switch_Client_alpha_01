package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.*
import io.switchlite.core.option.RandomRange
import io.switchlite.core.strategy.velocity.ClickVelocityStrategy
import io.switchlite.core.strategy.velocity.DelayVelocityStrategy
import io.switchlite.core.strategy.velocity.LegitVelocityStrategy
import io.switchlite.core.strategy.velocity.VelocityConfig
import io.switchlite.core.strategy.velocity.VelocityMode
import io.switchlite.core.strategy.velocity.VelocityResult
import io.switchlite.core.strategy.velocity.VelocityStrategy
import io.switchlite.core.util.Vec3
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.HudLineProvider
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * Velocity Module — knockback reduction.
 *
 * Modes: Legit (range-random scaling), Delay (buffer + release), Click (click-burst).
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
    private val mode by choices("Mode", arrayOf("Legit", "Delay", "Click"))

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

    // ========== Delay ==========
    private val delayMs by int("DelayMs", 0, 0..500, "ms")
    private val delayTicks by int("DelayTicks", 0, 0..20, "ticks")

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
    private val delayStrategy = DelayVelocityStrategy(legitStrategy)
    private val clickStrategy = ClickVelocityStrategy()
    private val strategyState = VelocityStrategy.State()

    // ========== OnlyOnHitFrame (deferred to tick — see onVelocityPacket) ==========
    /** Armed on the Netty thread when an S12 arrives with OnlyOnHitFrame on; consumed on the next
     *  20Hz tick if the player is on the hit frame (hurtResistantTime == maxHurtResistantTime). */
    @Volatile private var hitFramePending = false

    /**
     * LB-style hit-frame reduction: on the tick where the player's hurtResistantTime is still at
     * the max (or the tick right after — our 20Hz poll may drift a tick), scale the CURRENT motion
     * by the sampled h/v factors, exactly like LiquidBounce's legit mode does on UpdateEvent.
     */
    private val hitFrameTickListener: (PlayerState, TargetState?) -> Unit = { player, _ ->
        if (enabled && hitFramePending) {
            val maxHurt = player.maxHurtResistantTime
            if (maxHurt != 0 && player.hurtResistantTime >= maxHurt - 1) {
                // On the hit frame — apply the reduction once.
                hitFramePending = false
                if (Random.nextInt(100) < probability) {
                    val h = RandomRange.sample(horizontalMin, horizontalMax)
                    val v = RandomRange.sample(verticalMin, verticalMax)
                    val orig = Vec3(player.motionX, player.motionY, player.motionZ)
                    val reduced = Vec3(orig.x * h, orig.y * v, orig.z * h)
                    EventBridge.applyMotion(reduced)
                    EventBridge.velocityModified = true
                    val origSpeed = Math.sqrt(orig.x * orig.x + orig.z * orig.z)
                    val modSpeed = Math.sqrt(reduced.x * reduced.x + reduced.z * reduced.z)
                    EventBridge.recordKnockback(orig, modSpeed)
                }
            } else if (player.hurtResistantTime == 0) {
                // The hit frame passed without us catching it — no reduction this time.
                hitFramePending = false
            }
        }
    }

    // ========== Config Builder ==========
    private fun buildConfig(): VelocityConfig = VelocityConfig(
        mode = when (mode) {
            "Legit" -> VelocityMode.LEGIT
            "Delay" -> VelocityMode.DELAY
            "Click" -> VelocityMode.CLICK
            else -> VelocityMode.LEGIT
        },
        horizontalMin = horizontalMin,
        horizontalMax = horizontalMax,
        verticalMin = verticalMin,
        verticalMax = verticalMax,
        probability = probability,
        delayMs = delayMs,
        delayTicks = delayTicks,
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
        "Delay" -> delayStrategy
        "Click" -> clickStrategy
        else -> legitStrategy
    }

    // ========== Entry Point ==========

    /** Throttle for the velocity result diagnostic. */
    @Volatile private var velDiagCount = 0

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        val config = cachedConfig { buildConfig() }
        // OnlyOnHitFrame — LiquidBounce's VelocityLegit check, LB semantics: reduce ONLY on the
        // frame the player was just hit (`hurtResistantTime == maxHurtResistantTime`).
        //
        // Timing: the check CANNOT be evaluated on the Netty thread — the S12 is queued there but
        // the hit metadata (which sets hurtResistantTime) is only processed later on the MC main
        // thread, so on the Netty thread hurtResistantTime reads stale/0 and the gate would NEVER
        // fire. LB runs the legit reduction on the MAIN tick (UpdateEvent) for exactly this reason.
        // So we defer: arm [hitFramePending] here and let [hitFrameTickListener] apply the scaling
        // on the next 20Hz tick, when the hit frame is actually observable.
        if (onlyOnHitFrame && mode == "Legit") {
            hitFramePending = true
            EventBridge.velocityModified = false
            return PlatformCommand.Pass(ctx.originalMotion)
        }
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
            io.switchlite.core.logging.CoreLogger.info(
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
        EventBridge.unregisterVelocityListener()
        EventBridge.registerTickListener { currentTick ->
            if (enabled) onTick(currentTick)
        }
        EventBridge.registerTickListener(hitFrameTickListener)
        EventBridge.registerVelocityListener { ctx ->
            if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener()
        EventBridge.unregisterTickListener(this::onTick)
        EventBridge.unregisterTickListener(hitFrameTickListener)
        hitFramePending = false
        strategyState.reset()
    }

    /**
     * Called on every tick to drain delayed velocity packets from the
     * strategy's delay queue (managed by LegitVelocityStrategy/DelayVelocityStrategy).
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
