package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.strategy.aim.AimConfig
import io.switchlite.core.strategy.aim.AimInput
import io.switchlite.core.strategy.aim.AimResult
import io.switchlite.core.strategy.aim.AimStrategy
import io.switchlite.core.strategy.aim.LegitAimStrategy
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.triggerOptions

/**
 * AimAssist Module
 *
 * Architecture compliance (Sandwich):
 * 1. Logic in module: config assembly, input building, result mapping.
 * 2. Algorithm in Core: all humanization (overshoot FSM, reaction delay,
 *    box-edge tracking, noise injection) lives in [LegitAimStrategy].
 * 3. Platform agnostic: receives [PlayerState]/[TargetState] via parameters.
 * 4. Core dependency: only calls [AimStrategy.execute].
 */
object AimAssist : Module("AimAssist", Category.COMBAT) {

    // ========== Configuration (Delegated Properties) ==========
    // Range settings
    private val rangeMin by float("RangeMin", 3.0f, 0.0f..10.0f, "blocks")
    private val rangeMax by float("RangeMax", 6.0f, 0.0f..10.0f, "blocks")

    // FOV settings
    private val horizontalFov by float("HorizontalFOV", 90.0f, 0.0f..360.0f, "degrees")
    private val verticalFov by float("VerticalFOV", 60.0f, 0.0f..360.0f, "degrees")

    // Behavior settings
    private val aimSpeed by int("AimSpeed", 8, 1..20, "%")
    private val smoothness by float("Smoothness", 0.85f, 0.0f..1.0f)
    private val noiseIntensity by float("NoiseIntensity", 0.05f, 0.0f..0.5f)

    // Target selection (consumed by StateExtractor, not by Core strategy)
    @Suppress("unused")
    private val prioritizeDistance by boolean("PrioritizeDistance", true)

    // Mode: Legit (box edge) vs Normal (center/random)
    private val mode by choices("Mode", arrayOf("Legit", "Normal"))
    private val lockOnCrosshair by boolean("LockOnCrosshair", false)

    // Trigger conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        onlyCurrentView = true
        disableOnMine = true
        onlyOnClick = true
        chance = 100
    }

    // ========== Core Strategy (Algorithm lives here) ==========
    private val strategy = LegitAimStrategy()
    private val strategyState = AimStrategy.State()

    // ========== Config Builder ==========
    private fun buildConfig(): AimConfig = AimConfig(
        mode = when (mode) {
            "Legit" -> AimMode.LEGIT
            "Normal" -> AimMode.NORMAL
            else -> AimMode.LEGIT
        },
        rangeMin = rangeMin,
        rangeMax = rangeMax,
        horizontalFov = horizontalFov,
        verticalFov = verticalFov,
        aimSpeed = aimSpeed,
        smoothness = smoothness,
        noiseIntensity = noiseIntensity,
        lockOnCrosshair = lockOnCrosshair,
        triggerOptions = triggerOptions
    )

    // ========== Tick Listener Reference ==========
    private var tickListener: ((PlayerState, TargetState?) -> Unit)? = null

    // ========== Event Handler (Platform Agnostic, Thin Glue Layer) ==========

    /**
     * Called by EventBridge on every client tick.
     * Assembles config and input, delegates to Core strategy, maps result.
     * NO algorithm logic here — only ~15 lines of glue code.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        val config = buildConfig()
        val input = AimInput(player, target)
        val result = strategy.execute(config, strategyState, input)

        when (result) {
            is AimResult.ApplyRotation -> EventBridge.setPlayerRotation(result.rotation)
            is AimResult.Skip -> { /* no-op */ }
        }
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        strategyState.reset()
        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        strategyState.reset()
    }
}
