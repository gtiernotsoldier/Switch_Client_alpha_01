package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.*
import io.switchlite.core.strategy.velocity.ClickVelocityStrategy
import io.switchlite.core.strategy.velocity.DelayVelocityStrategy
import io.switchlite.core.strategy.velocity.LegitVelocityStrategy
import io.switchlite.core.strategy.velocity.VelocityConfig
import io.switchlite.core.strategy.velocity.VelocityMode
import io.switchlite.core.strategy.velocity.VelocityResult
import io.switchlite.core.strategy.velocity.VelocityStrategy
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.triggerOptions

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
object Velocity : Module("Velocity", Category.COMBAT) {

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

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        val config = buildConfig()
        val result = currentStrategy().execute(config, strategyState, ctx)
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
        EventBridge.registerVelocityListener { ctx ->
            if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
        }
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener()
        EventBridge.unregisterTickListener(this::onTick)
        strategyState.reset()
    }

    /**
     * Called on every tick to drain delayed velocity packets from the
     * strategy's delay queue (managed by LegitVelocityStrategy/DelayVelocityStrategy).
     */
    fun onTick(currentTick: Int) {
        val config = buildConfig()
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
