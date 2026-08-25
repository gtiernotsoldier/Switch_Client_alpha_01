package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.AimMode
import io.switchlite.core.strategy.aim.AimConfig
import io.switchlite.core.strategy.aim.AimInput
import io.switchlite.core.strategy.aim.AimResult
import io.switchlite.core.strategy.aim.AimStrategy
import io.switchlite.core.strategy.aim.LegitAimStrategy
import io.switchlite.core.strategy.aim.SelfAdaptiveAimStrategy
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
 *    box-edge tracking, noise injection, adaptive alignment) lives in
 *    [LegitAimStrategy] or [SelfAdaptiveAimStrategy].
 * 3. Platform agnostic: receives [PlayerState]/[TargetState] via parameters.
 * 4. Core dependency: only calls [AimStrategy.execute].
 */
object AimAssist : Module("AimAssist", Category.COMBAT) {

    init {
        // Aim is a stealth module — no visible red indicator on HUD
        showRedIndicator = false
    }

    // ========== Configuration (Delegated Properties) ==========
    // Range settings (3D distance, blocks)
    private val rangeMin by float("RangeMin", 3.0f, 0.0f..10.0f, "blocks")
    private val rangeMax by float("RangeMax", 6.0f, 0.0f..10.0f, "blocks")

    // FOV setting — 360 = full 360° (skip the cone gate entirely, Raven-XD default); lower values
    // restrict the pull to an angular cone around the view line.
    private val fov by float("Fov", 360.0f, 0.0f..360.0f, "degrees")

    // Behavior settings
    private val aimSpeed by int("AimSpeed", 8, 1..20, "%")
    private val smoothness by float("Smoothness", 0.85f, 0.0f..1.0f)
    private val noiseIntensity by float("NoiseIntensity", 0.05f, 0.0f..0.5f)

    // Mode: Legit (box edge) vs Normal (crosshair-point lock) vs SelfAdaptive (adaptive)
    private val mode by choices("Mode", arrayOf("Legit", "Normal", "SelfAdaptive"))
    /**
     * LockOnCrosshair — when ON, only assist once the crosshair is already aligned to the target
     * (within a small angle). Off = assist anywhere inside the FOV cone.
     */
    private val lockOnCrosshair by boolean("LockOnCrosshair", false)

    // Trigger conditions (Unified Engine)
    /** Only aim while attacking (physical click OR AutoClicker active). Off = aim whenever the
     *  target is in range/FOV, even if not clicking. */
    private val onlyOnClick by boolean("OnlyOnClick", true)
    private val triggerOptions by triggerOptions("Trigger") {
        // NOTE: onlyCurrentView intentionally OFF — it would require the crosshair to already be on
        // the target, which blocks the pull-back when the crosshair is slightly off (that is the
        // whole point of the assist). FOV (default 360 = full) gates the scope instead.
        disableOnMine = true
        onlyOnClick = this@AimAssist.onlyOnClick
        chance = 100
    }

    // ========== Core Strategies (Algorithm lives here) ==========
    private val legitStrategy = LegitAimStrategy()
    private val legitState = AimStrategy.State()
    private val adaptiveStrategy = SelfAdaptiveAimStrategy()
    private val adaptiveState = SelfAdaptiveAimStrategy.AdaptiveState()

    // ========== Config Builder (shared by all modes) ==========
    private fun buildConfig(modeOverride: AimMode? = null): AimConfig = AimConfig(
        mode = modeOverride ?: when (mode) {
            "Legit" -> AimMode.LEGIT
            "Normal" -> AimMode.NORMAL
            "SelfAdaptive" -> AimMode.SELF_ADAPTIVE
            else -> AimMode.LEGIT
        },
        rangeMin = rangeMin,
        rangeMax = rangeMax,
        fov = fov,
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
     * Routes to Legit/Normal (via LegitAimStrategy) or SelfAdaptive (via SelfAdaptiveAimStrategy).
     * NO algorithm logic here — only config assembly + result mapping.
     *
     * Target selection mirrors Nemui: prefer the nearest viable entity inside the FOV cone + range
     * (so the crosshair gets "pulled back" toward it even when slightly off), falling back to the
     * generic tick target.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        val aimTarget = EventBridge.getFovNearestTarget(fov, rangeMax) ?: target

        // AutoClicker/TriggerBot active -> the player is effectively attacking continuously, so
        // the onlyOnClick trigger (which reads the PHYSICAL attack key) must not block aiming.
        // Mark isAttackKeyDown so onlyOnClick passes; never touches the global field.
        val effPlayer = if (EventBridge.syntheticAttackOverride) {
            player.copy(isAttackKeyDown = true)
        } else {
            player
        }
        when (mode) {
            "SelfAdaptive" -> {
                val config = cachedConfig("adaptive") { buildConfig(AimMode.SELF_ADAPTIVE) }
                val input = AimInput(
                    player = effPlayer,
                    target = aimTarget,
                    mouseDeltaX = EventBridge.mouseDeltaX,
                    mouseDeltaY = EventBridge.mouseDeltaY,
                    sensitivity = EventBridge.mouseSensitivity
                )
                val result = adaptiveStrategy.execute(config, adaptiveState, input)
                when (result) {
                    // Write the desired rotation; the MAIN thread applies it (see drainDesiredRotation).
                    is AimResult.ApplyRotation -> {
                        EventBridge.desiredRotationYaw = result.rotation.yaw
                        EventBridge.desiredRotationPitch = result.rotation.pitch
                    }
                    is AimResult.Skip -> { /* no-op */ }
                }
            }
            else -> {
                // Legit / Normal — delegate to LegitAimStrategy
                val config = cachedConfig("legit") { buildConfig() }
                val input = AimInput(effPlayer, aimTarget)
                val result = legitStrategy.execute(config, legitState, input)
                when (result) {
                    // Write the desired rotation; the MAIN thread applies it (see drainDesiredRotation).
                    is AimResult.ApplyRotation -> {
                        EventBridge.desiredRotationYaw = result.rotation.yaw
                        EventBridge.desiredRotationPitch = result.rotation.pitch
                    }
                    is AimResult.Skip -> { /* no-op */ }
                }
            }
        }
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        legitState.reset()
        adaptiveState.reset()
        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        legitState.reset()
        adaptiveState.reset()
    }
}
