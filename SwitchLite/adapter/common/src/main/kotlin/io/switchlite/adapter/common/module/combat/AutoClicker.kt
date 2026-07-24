package io.switchlite.adapter.common.module.combat

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.click.ClickInput
import io.switchlite.core.strategy.click.ClickResult
import io.switchlite.core.strategy.click.CooldownClickConfig
import io.switchlite.core.strategy.click.CooldownClickMode
import io.switchlite.core.strategy.click.CooldownClickStrategy
import io.switchlite.core.strategy.click.CritMode
import io.switchlite.core.strategy.click.WeaponFilter
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.core.strategy.click.OnItemUse
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.triggerOptions

/**
 * AutoClicker Module
 *
 * Supports two combat paradigms via combatVersion setting:
 * - **1.8**: CPS-based clicking (no cooldown). 1.7.10 / 1.8.9.
 * - **1.9+**: Cooldown-bar based clicking (1.9+).
 *
 * Architecture Compliance:
 * 1. Pure Logic: No Minecraft/Forge/Fabric imports.
 * 2. Unified Config: Uses delegated properties for hot-reloading.
 * 3. Platform Agnostic: Receives state via parameters, not direct game access.
 * 4. Core Dependency: 1.8 calls pure math algorithms from core/algorithm;
 *    1.9+ delegates to CooldownClickStrategy from core/strategy/click.
 *
 * Blind principle: This module does not select targets or check crosshair
 * alignment. It clicks whenever trigger conditions are met (attack key held,
 * not mining, etc.), regardless of who or what is under the crosshair.
 * Target selection and aim are handled by AimAssist.
 */
object AutoClicker : Module("AutoClicker", Category.COMBAT) {

    // ====================================================================
    // Version Selection
    // ====================================================================

    /**
     * Combat version. Determines which config and logic path to use.
     * GUI-visible; adapter bootstrap sets the initial value.
     */
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

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
    private val clickMode by choices("ClickMode", arrayOf("Single", "Double"))

    // Mode: NORMAL or LEGIT (dynamic CPS based on distance)
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ====================================================================
    // 1.9+ Configuration
    // ====================================================================

    /** Cooldown threshold: 50%-100% of the cooldown bar. Default 100%. */
    private val cooldownThreshold by float("CooldownThreshold", 1.0f, 0.5f..1.0f, "%")

    /** Crit mode: Off / On / Smart */
    private val critMode by choices("CritMode", arrayOf("Off", "On", "Smart"))

    /** Auto-stop sprinting before crit, restore after. Only affects On and Smart. */
    private val critStopSprint by boolean("CritStopSprint", true)

    /** Weapon filter: only click when holding a matching weapon. */
    private val weaponFilter by choices("WeaponFilter", arrayOf("Any", "Sword", "Axe", "Sword&Axe"))

    /** Behaviour when player is using an item (blocking, eating, etc.). */
    private val onItemUse by choices("OnItemUse", arrayOf("Wait", "Stop", "Ignore"))

    /** 1.9+ click mode: Normal (instant) or Legit (random 1-3t delay). */
    private val mode19 by choices("Mode19", arrayOf("Normal", "Legit"))

    // ====================================================================
    // Shared Configuration
    // ====================================================================

    // Trigger conditions (Unified Engine)
    private val triggerOptions by triggerOptions("Trigger") {
        // onlyCurrentView: disabled. This module is blind — it does not check
        // whether the crosshair is on a target. AimAssist handles aiming.
        onlyCurrentView = false
        disableOnMine = false
        onlyOnClick = true
        chance = 100
    }

    // Block hit prevention
    private val disableOnBlock by boolean("DisableOnBlock", true)

    // ========== Runtime Dependencies (Injected by Core) ==========
    private val conditionChecker = ConditionChecker

    // ========== 1.8 Timing State ==========
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
            "1.8" -> onTick18(player, target)
            "1.9+" -> onTick19(player, target)
        }
    }

    // ====================================================================
    // 1.8 Logic
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

        // 3. Blind gate: pass null so target-identity conditions are skipped.
        //    Only non-target conditions are checked (onlyOnClick, disableOnMine, etc.)
        if (!conditionChecker.check(triggerOptions, player, null)) return

        // 4. Calculate Effective CPS
        //    LEGIT borrows target.distance for human-like CPS adjustment;
        //    target null → falls back to base (no distance modifier).
        val base = sampleCpsInRange()
        val effectiveCps = if (mode == "Legit" && target != null) {
            adjustCpsByDistance(base, target.distance)
        } else {
            base
        }.coerceAtLeast(1)

        // 5. Probability-based click (effectiveCps / 20.0 chance per tick)
        val clickChance = effectiveCps / 20.0
        if (NoiseProvider.nextUniform(0f, 1f) >= clickChance) return

        // 6. Execute Click(s) via Bridge
        when (clickMode) {
            "Single" -> {
                EventBridge.triggerAttack()
            }
            "Double" -> {
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
        // --- Adapter-level pre-checks (before strategy) ---

        // 1. Item use check (covers shield blocking, bow drawing, eating, drinking, etc.)
        if (player.isUsingItem) {
            when (onItemUse) {
                "Wait" -> return
                "Stop" -> {
                    EventBridge.releaseUsingItem()
                    // continue to weapon filter + strategy
                }
                "Ignore" -> { /* continue */ }
            }
        }

        // 2. Weapon filter check
        if (weaponFilter != "Any") {
            val passes = when (weaponFilter) {
                "Sword" -> player.weaponType == WeaponType.SWORD
                "Axe" -> player.weaponType == WeaponType.AXE
                "Sword&Axe" -> player.weaponType == WeaponType.SWORD || player.weaponType == WeaponType.AXE
                else -> true
            }
            if (!passes) return
        }

        // --- Delegate to core strategy (blind: target = null) ---
        val critModeEnum = when (critMode) {
            "On" -> CritMode.ON
            "Smart" -> CritMode.SMART
            else -> CritMode.OFF
        }
        val mode19Enum = if (mode19 == "Legit") CooldownClickMode.LEGIT else CooldownClickMode.NORMAL
        val config = CooldownClickConfig(
            cooldownThreshold = cooldownThreshold,
            critMode = critModeEnum,
            critStopSprint = critStopSprint,
            cooldownMode = mode19Enum,
            disableOnBlock = disableOnBlock,
            triggerOptions = triggerOptions
        )
        val input = ClickInput(
            player = player,
            target = null, // 1.9+ is fully blind
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
     * LEGIT mode: adjust base CPS by target distance (1.8 only).
     * - Distance > jittered 2.6–3.0 blocks: reduce by 2–5
     * - Distance < 1.5 blocks: increase by 3–5
     * - Otherwise: no change
     */
    private fun adjustCpsByDistance(baseCps: Int, distance: Float): Int {
        val lo = minCps.coerceAtMost(maxCps)
        val hi = maxCps.coerceAtLeast(minCps)
        val adjusted = when {
            distance > NoiseProvider.nextUniform(2.6f, 3.0f) -> {
                val reduction = NoiseProvider.nextUniform(2f, 6f).toInt().coerceIn(2, 5)
                baseCps - reduction
            }
            distance < 1.5f -> {
                val boost = NoiseProvider.nextUniform(3f, 6f).toInt().coerceIn(3, 5)
                baseCps + boost
            }
            else -> baseCps
        }
        return adjusted.coerceIn(lo, hi)
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
        pendingSecondClick = false
        // Reset 1.9+ state
        state19.reset()
    }
}