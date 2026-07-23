package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.ClickMode
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.enum
import io.switchlite.adapter.common.option.triggerOptions

/**
 * AutoClicker Module
 * 
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters, not direct game access.
 * 4. Core Dependency: Only calls pure math algorithms from core/algorithm.
 */
object AutoClicker : Module("AutoClicker", Category.COMBAT) {

    // ========== Configuration (Delegated Properties) ==========
    // CPS settings
    private val maxCps by int("MaxCPS", 10, 0..20, "cps")
    private val minCps by int("MinCPS", 8, 0..20, "cps")

    // Click mode: SINGLE or DOUBLE
    private val clickMode by enum("ClickMode", ClickMode.SINGLE)

    // Mode: NORMAL or LEGIT (dynamic CPS based on distance)
    private val mode by enum("Mode", AutoClickerMode.NORMAL)

    // Trigger conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        onlyCurrentView = true
        disableOnMine = false
        onlyOnClick = true
        chance = 100
    }

    // Block hit prevention
    private val disableOnBlock by boolean("DisableOnBlock", true)

    // ========== Runtime Dependencies (Injected by Core) ==========
    private val conditionChecker = ConditionChecker

    // ========== Timing State ==========
    private var ticksUntilNextClick = 0
    private var lastTargetId = -1

    // ========== Tick Listener Reference ==========
    private var tickListener: ((PlayerState, TargetState?) -> Unit)? = null

    // ========== Event Handler (Platform Agnostic) ==========

    /**
     * Called by EventBridge on every client tick.
     * Receives pure data snapshots (PlayerState, TargetState).
     * NO Minecraft object access allowed here.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        // 1. Block Hit Prevention — skip if player is mining a block
        if (disableOnBlock && player.isMining) return

        // 2. Condition Check (Unified Engine)
        if (target == null || !conditionChecker.check(triggerOptions, player, target)) return

        // 3. Target Change Detection — reset timing on new target
        if (target.entityId != lastTargetId) {
            lastTargetId = target.entityId
            ticksUntilNextClick = sampleClickDelay()
        }

        // 4. Countdown
        if (ticksUntilNextClick > 0) {
            ticksUntilNextClick--
            return
        }

        // 5. Calculate Effective CPS
        val effectiveCps = when (mode) {
            AutoClickerMode.NORMAL -> sampleCpsInRange()
            AutoClickerMode.LEGIT -> {
                val baseCps = sampleCpsInRange()
                adjustCpsByDistance(baseCps, target.distance)
            }
        }.coerceAtLeast(1)

        // 6. Convert CPS to tick delay (20 TPS) and schedule next click
        ticksUntilNextClick = (20.0 / effectiveCps).toInt().coerceAtLeast(1)

        // 7. Execute Click(s) via Bridge
        when (clickMode) {
            ClickMode.SINGLE -> {
                EventBridge.triggerAttack()
            }
            ClickMode.DOUBLE -> {
                EventBridge.triggerAttack()
                EventBridge.triggerAttack()
            }
        }
    }

    // ========== Helper Methods (Pure Logic) ==========

    /**
     * Sample a CPS value uniformly within [minCps, maxCps].
     */
    private fun sampleCpsInRange(): Int {
        val lo = minCps.coerceAtMost(maxCps)
        val hi = maxCps.coerceAtLeast(minCps)
        if (lo == hi) return lo
        return lo + (NoiseProvider.nextUniform(0f, 1f) * (hi - lo + 1)).toInt().coerceIn(0, hi - lo)
    }

    /**
     * Adjust CPS based on target distance for LEGIT mode.
     * - Distance > 6 blocks: reduce CPS by 2-5
     * - Distance < 3 blocks: increase CPS by 3-5
     * - Otherwise: no change
     */
    private fun adjustCpsByDistance(baseCps: Int, distance: Float): Int {
        val lo = minCps.coerceAtMost(maxCps)
        val hi = maxCps.coerceAtLeast(minCps)
        val adjusted = when {
            distance > 6.0f -> {
                val reduction = NoiseProvider.nextUniform(2f, 5f).toInt()
                baseCps - reduction
            }
            distance < 3.0f -> {
                val boost = NoiseProvider.nextUniform(3f, 5f).toInt()
                baseCps + boost
            }
            else -> baseCps
        }
        return adjusted.coerceIn(lo, hi)
    }

    /**
     * Sample initial click delay in ticks.
     * Converts a random CPS within range to tick delay.
     */
    private fun sampleClickDelay(): Int {
        val cps = sampleCpsInRange().coerceAtLeast(1)
        return (20.0 / cps).toInt().coerceAtLeast(1)
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        ticksUntilNextClick = 0
        lastTargetId = -1
    }
}

/**
 * AutoClicker operating mode.
 * NORMAL: fixed random CPS within range.
 * LEGIT: dynamic CPS adjusted by target distance.
 */
enum class AutoClickerMode { NORMAL, LEGIT }
