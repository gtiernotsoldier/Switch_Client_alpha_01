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
    private val aimSpeed by int("AimSpeed", 5, 1..20, "deg/tick")
    private val smoothness by float("Smoothness", 0.85f, 0.0f..1.0f)
    private val noiseIntensity by float("NoiseIntensity", 0.05f, 0.0f..0.5f)
    /** Natural aim offset (degrees): the aim point drifts slowly within ±offset so the crosshair
     *  never locks dead-on — reads like a human hand, not a machine. */
    private val offset by float("Offset", 0.5f, 0.0f..3.0f, "degrees")

    // Mode: Legit (box edge) vs Normal (crosshair-point lock) vs SelfAdaptive (adaptive)
    private val mode by choices("Mode", arrayOf("Legit", "Normal", "SelfAdaptive"))
    /**
     * LockOnCrosshair — when ON, only assist once the crosshair is already aligned to the target
     * (within a small angle). Off = assist anywhere inside the FOV cone.
     */
    private val lockOnCrosshair by boolean("LockOnCrosshair", false)
    /**
     * OnlyCrosshairTarget — when ON, target selection only ever uses the entity under the
     * crosshair (objectMouseOver). It will NOT pick a different nearby entity from the FOV+range
     * scan. Use it when you want the assist to only ever follow what your crosshair is on.
     */
    private val onlyCrosshairTarget by boolean("OnlyCrosshairTarget", false)

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
        offset = offset,
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
     * Target selection: with OnlyCrosshairTarget ON, only the entity under the crosshair
     * (EventBridge.crosshairTarget) is ever used. Otherwise, mirror Nemui: nearest viable entity
     * inside the FOV cone + range (so the crosshair gets pulled back even when slightly off),
     * falling back to the generic tick target.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        val aimTarget = if (onlyCrosshairTarget) {
            EventBridge.crosshairTarget ?: target
        } else {
            EventBridge.getFovNearestTarget(fov, rangeMax) ?: target
        }

        // "Clicking" = physically holding the attack button OR a synthetic click fired THIS tick
        // (AutoClicker/TriggerBot write syntheticAttack=true only while actually clicking). Using
        // syntheticAttack — not syntheticAttackOverride (module enabled) — keeps OnlyOnClick
        // honest: a clicker that is enabled but not currently clicking does NOT make us aim.
        val clicking = player.isAttackKeyDown || EventBridge.syntheticAttack
        val effPlayer = if (clicking) player.copy(isAttackKeyDown = true) else player
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
                    // Write target + per-frame fraction; the MAIN thread interpolates every frame.
                    is AimResult.ApplyRotation -> {
                        EventBridge.desiredRotationYaw = result.rotation.yaw
                        EventBridge.desiredRotationPitch = result.rotation.pitch
                        EventBridge.desiredRotationFraction = result.fraction
                    }
                    is AimResult.Skip -> {
                        // No assist this tick — clear the pending target so the main thread stops
                        // pulling toward the last remembered point (fixes "aims at one fixed spot").
                        EventBridge.clearDesiredRotation()
                    }
                }
            }
            else -> {
                // Legit / Normal — delegate to LegitAimStrategy
                val config = cachedConfig("legit") { buildConfig() }
                val input = AimInput(
                    player = effPlayer,
                    target = aimTarget,
                    mouseDeltaX = EventBridge.mouseDeltaX,
                    mouseDeltaY = EventBridge.mouseDeltaY,
                    sensitivity = EventBridge.mouseSensitivity
                )
                val result = legitStrategy.execute(config, legitState, input)
                when (result) {
                    // Write target + per-frame fraction; the MAIN thread interpolates every frame.
                    is AimResult.ApplyRotation -> {
                        EventBridge.desiredRotationYaw = result.rotation.yaw
                        EventBridge.desiredRotationPitch = result.rotation.pitch
                        EventBridge.desiredRotationFraction = result.fraction
                    }
                    is AimResult.Skip -> {
                        // No assist this tick — clear the pending target so the main thread stops
                        // pulling toward the last remembered point (fixes "aims at one fixed spot").
                        EventBridge.clearDesiredRotation()
                    }
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
        // Stop any in-flight frame interpolation immediately.
        EventBridge.clearDesiredRotation()
    }
}
