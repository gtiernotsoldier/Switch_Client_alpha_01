package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.ClickMode
import io.switchlite.core.strategy.click.ClickInput
import io.switchlite.core.strategy.click.ClickResult
import io.switchlite.core.strategy.click.CooldownClickConfig
import io.switchlite.core.strategy.click.CooldownClickMode
import io.switchlite.core.strategy.click.CooldownClickStrategy
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.enum
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.triggerOptions

/**
 * AutoClicker Module
 *
 * Supports two combat paradigms via [CombatVersion]:
 * - **V1_8**: CPS-based clicking (no cooldown). 1.7.10 / 1.8.9.
 * - **V1_9_PLUS**: Cooldown-bar based clicking (1.9+).
 *
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters, not direct game access.
 * 4. Core Dependency: 1.8 calls pure math algorithms from core/algorithm;
 *    1.9+ delegates to CooldownClickStrategy from core/strategy/click.
 */
object AutoClicker : Module("AutoClicker", Category.COMBAT) {

    // ====================================================================
    // Version Selection
    // ====================================================================

    /**
     * Combat version. Determines which config and logic path to use.
     * The version-specific adapter (Forge 1.8.9 / Fabric 1.21) should
     * set this during bootstrap.
     */
    var combatVersion: CombatVersion = CombatVersion.V1_8

    /**
     * Provider for the player's attack cooldown (0.0–1.0).
     * Injected by the 1.9+ adapter. Returns 1.0 (always ready) if not set.
     */
    var attackCooldownProvider: (() -> Float) = { 1.0f }

    // ========== 1.8 Configuration (Delegated Properties) ==========
    // CPS settings
    private val maxCps by int("MaxCPS", 10, 0..20, "cps")
    private val minCps by int("MinCPS", 8, 0..20, "cps")

    // Click mode: SINGLE or DOUBLE
    private val clickMode by enum("ClickMode", ClickMode.SINGLE)

    // Mode: NORMAL or LEGIT (dynamic CPS based on distance)
    private val mode by enum("Mode", AutoClickerMode.NORMAL)

    // ====================================================================
    // 1.9+ Configuration
    // ====================================================================

    /** Cooldown threshold: 50%-100% of the cooldown bar. Default 100%. */
    private val cooldownThreshold by float("CooldownThreshold", 1.0f, 0.5f..1.0f, "%")

    /** Enable critical hit logic (every hit is a crit). */
    private val critEnabled by boolean("CritEnabled", false)

    /** Auto-stop sprinting before crit, restore after. */
    private val critStopSprint by boolean("CritStopSprint", true)

    /** 1.9+ click mode: NORMAL (instant) or LEGIT (random 1-3t delay). */
    private val mode19 by enum("Mode19", CooldownClickMode.NORMAL)

    // ====================================================================
    // Shared Configuration
    // ====================================================================

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

    // ========== 1.8 Timing State ==========
    private var ticksUntilNextClick = 0
    private var lastTargetId = -1
    private var pendingSecondClick = false

    // ====================================================================
    // 1.9+ Strategy & State
    // ====================================================================

    private val strategy19 = CooldownClickStrategy()
    private val state19 = CooldownClickStrategy.CritState()

    // ========== Tick Listener Reference ==========
    private var tickListener: ((PlayerState, TargetState?) -> Unit)? = null

    // ====================================================================
    // Entry Point
    // ====================================================================

    /**
     * Called by EventBridge on every client tick.
     * Routes to the appropriate version handler.
     */
    fun onClientTick(player: PlayerState, target: TargetState?) {
        when (combatVersion) {
            CombatVersion.V1_8 -> onTick18(player, target)
            CombatVersion.V1_9_PLUS -> onTick19(player, target)
        }
    }

    // ====================================================================
    // 1.8 Logic (unchanged)
    // ====================================================================

    private fun onTick18(player: PlayerState, target: TargetState?) {
        // 1. Block Hit Prevention — skip if player is mining a block
        if (disableOnBlock && player.isMining) return

        // 2. Handle pending second click from double-click mode
        if (pendingSecondClick) {
            pendingSecondClick = false
            EventBridge.triggerAttack()
            return
        }

        // 3. Condition Check (Unified Engine)
        if (target == null || !conditionChecker.check(triggerOptions, player, target)) return

        // 4. Target Change Detection — reset timing on new target
        if (target.entityId != lastTargetId) {
            lastTargetId = target.entityId
        }

        // 5. Calculate Effective CPS
        val effectiveCps = when (mode) {
            AutoClickerMode.NORMAL -> sampleCpsInRange()
            AutoClickerMode.LEGIT -> {
                val baseCps = sampleCpsInRange()
                adjustCpsByDistance(baseCps, target.distance)
            }
        }.coerceAtLeast(1)

        // 6. Probability-based click (effectiveCps / 20.0 chance per tick)
        val clickChance = effectiveCps / 20.0
        if (NoiseProvider.nextUniform(0f, 1f) >= clickChance) return

        // 7. Execute Click(s) via Bridge
        when (clickMode) {
            ClickMode.SINGLE -> {
                EventBridge.triggerAttack()
            }
            ClickMode.DOUBLE -> {
                // First click immediately, second click delayed to next tick
                EventBridge.triggerAttack()
                pendingSecondClick = true
            }
        }
    }

    // ====================================================================
    // 1.9+ Logic — delegates to CooldownClickStrategy
    // ====================================================================

    private fun onTick19(player: PlayerState, target: TargetState?) {
        val config = CooldownClickConfig(
            cooldownThreshold = cooldownThreshold,
            critEnabled = critEnabled,
            critStopSprint = critStopSprint,
            cooldownMode = mode19,
            disableOnBlock = disableOnBlock,
            triggerOptions = triggerOptions
        )
        val input = ClickInput(
            player = player,
            target = target,
            attackCooldown = attackCooldownProvider(),
            isFalling = player.motionY < 0.0 && !player.onGround
        )
        val result = strategy19.processTick(config, state19, input)
        applyResult19(result)
    }

    /**
     * Map a [ClickResult] from CooldownClickStrategy to EventBridge calls.
     */
    private fun applyResult19(result: ClickResult) {
        when (result) {
            is ClickResult.Click -> EventBridge.triggerAttack()
            is ClickResult.StopSprint -> EventBridge.setSprinting(false)
            is ClickResult.RestoreSprint -> EventBridge.setSprinting(result.wasSprinting)
            is ClickResult.Skip -> { /* no-op */ }
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
        // Reset 1.9+ strategy state
        state19.reset()
        strategy19.reset()

        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        // Reset 1.8 state
        ticksUntilNextClick = 0
        lastTargetId = -1
        pendingSecondClick = false
        // Reset 1.9+ state
        state19.reset()
    }
}

// ====================================================================
// Enums
// ====================================================================

/**
 * Combat version — determines which click paradigm to use.
 */
enum class CombatVersion {
    /** 1.8 and below: CPS-based, no cooldown. */
    V1_8,
    /** 1.9+: cooldown bar governs attack speed. */
    V1_9_PLUS
}

/**
 * AutoClicker operating mode.
 * NORMAL: fixed random CPS within range.
 * LEGIT: dynamic CPS adjusted by target distance.
 */
enum class AutoClickerMode { NORMAL, LEGIT }
