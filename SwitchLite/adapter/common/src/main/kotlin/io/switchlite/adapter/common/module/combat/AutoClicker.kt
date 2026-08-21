package io.switchlite.adapter.common.module.combat

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.ClickMode
import io.switchlite.core.strategy.click.ClickConfig
import io.switchlite.core.strategy.click.ClickInput
import io.switchlite.core.strategy.click.ClickOperatingMode
import io.switchlite.core.strategy.click.ClickResult
import io.switchlite.core.strategy.click.ClickStrategy
import io.switchlite.core.strategy.click.CooldownClickConfig
import io.switchlite.core.strategy.click.CooldownClickMode
import io.switchlite.core.strategy.click.CooldownClickStrategy
import io.switchlite.core.strategy.click.CritMode
import io.switchlite.core.strategy.click.ProbabilisticClickStrategy
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.HudLineProvider
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
 * - **1.8**: CPS-based clicking — delegates to [ProbabilisticClickStrategy].
 * - **1.9+**: Cooldown-bar based clicking — delegates to [CooldownClickStrategy].
 *
 * Architecture compliance (Sandwich):
 * 1. Logic in module: config assembly, input building, result mapping.
 * 2. Algorithm in Core: all CPS probability, distance adjustment (1.8),
 *    cooldown timing, and crit state machine (1.9+) live in Core strategies.
 * 3. Platform agnostic: receives [PlayerState]/[TargetState] via parameters.
 *
 * Blind principle: This module does not select targets or check crosshair
 * alignment. It clicks whenever trigger conditions are met (attack key held,
 * not mining, etc.), regardless of who is under the crosshair.
 * Target selection and aim are handled by AimAssist.
 */
object AutoClicker : Module("AutoClicker", Category.COMBAT), HudLineProvider {

    init {
        // AutoClicker is a stealth module — no visible red indicator on HUD
        showRedIndicator = false
    }

    // ── HUD value (event-driven; read once on enable/config change) ──
    override fun hudValue(): String = "$minCps-$maxCps CPS"
    override fun hudHighlight(): Boolean = true

    // ====================================================================
    // Version Selection
    // ====================================================================

    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    // ========== 1.8 Configuration (Delegated Properties) ==========
    private val maxCps by int("MaxCPS", 10, 0..20, "cps")
    private val minCps by int("MinCPS", 8, 0..20, "cps")
    private val clickMode by choices("ClickMode", arrayOf("Single", "Double"))
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ====================================================================
    // 1.9+ Configuration
    // ====================================================================
    private val cooldownThreshold by float("CooldownThreshold", 1.0f, 0.5f..1.0f, "%")
    private val critMode by choices("CritMode", arrayOf("Off", "On", "Smart"))
    private val critStopSprint by boolean("CritStopSprint", true)
    private val weaponFilter by choices("WeaponFilter", arrayOf("Any", "Sword", "Axe", "Sword&Axe"))
    private val onItemUse by choices("OnItemUse", arrayOf("Wait", "Stop", "Ignore"))
    private val mode19 by choices("Mode19", arrayOf("Normal", "Legit"))

    // ====================================================================
    // Shared Configuration
    // ====================================================================
    private val triggerOptions by triggerOptions("Trigger") {
        onlyCurrentView = false
        disableOnMine = false
        onlyOnClick = true
        chance = 100
    }
    private val disableOnBlock by boolean("DisableOnBlock", true)

    // ====================================================================
    // Core Strategies (Algorithm lives here)
    // ====================================================================
    // 1.8
    private val strategy18 = ProbabilisticClickStrategy()
    private val state18 = ClickStrategy.State()
    // 1.9+
    private val strategy19 = CooldownClickStrategy()
    private val state19 = CooldownClickStrategy.CritState()

    // ========== Tick Listener Reference ==========
    private var tickListener: ((PlayerState, TargetState?) -> Unit)? = null

    // ====================================================================
    // Entry Point
    // ====================================================================

    fun onClientTick(player: PlayerState, target: TargetState?) {
        when (combatVersion) {
            "1.8" -> onTick18(player, target)
            "1.9+" -> onTick19(player, target)
        }
    }

    // ====================================================================
    // 1.8 Logic — delegates to ProbabilisticClickStrategy
    // ====================================================================

    private fun onTick18(player: PlayerState, target: TargetState?) {
        val config = cachedConfig("18") { ClickConfig(
            minCps = minCps,
            maxCps = maxCps,
            clickMode = when (clickMode) {
                "Double" -> ClickMode.DOUBLE
                "Single" -> ClickMode.SINGLE
                else -> ClickMode.SINGLE
            },
            mode = when (mode) {
                "Legit" -> ClickOperatingMode.LEGIT
                "Normal" -> ClickOperatingMode.NORMAL
                else -> ClickOperatingMode.NORMAL
            },
            disableOnBlock = disableOnBlock,
            triggerOptions = triggerOptions
        ) }
        val input = ClickInput(player = player, target = target)
        val result = strategy18.execute(config, state18, input)

        when (result) {
            is ClickResult.Click -> EventBridge.syntheticAttack = true
            is ClickResult.Skip -> EventBridge.syntheticAttack = false
            is ClickResult.StopSprint -> { /* not used in 1.8 path */ }
            is ClickResult.RestoreSprint -> { /* not used in 1.8 path */ }
        }
    }

    // ====================================================================
    // 1.9+ Logic — delegates to CooldownClickStrategy (already correct)
    // ====================================================================

    private fun onTick19(player: PlayerState, target: TargetState?) {
        // --- Adapter-level pre-checks (before strategy) ---

        // 1. Item use check
        if (player.isUsingItem) {
            when (onItemUse) {
                "Wait" -> return
                "Stop" -> {
                    EventBridge.releaseUsingItem()
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

        // --- Delegate to Core strategy ---
        val critModeEnum = when (critMode) {
            "On" -> CritMode.ON
            "Smart" -> CritMode.SMART
            "Off" -> CritMode.OFF
            else -> CritMode.OFF
        }
        val mode19Enum = when (mode19) {
            "Legit" -> CooldownClickMode.LEGIT
            "Normal" -> CooldownClickMode.NORMAL
            else -> CooldownClickMode.NORMAL
        }
        val config = cachedConfig("19") { CooldownClickConfig(
            cooldownThreshold = cooldownThreshold,
            critMode = critModeEnum,
            critStopSprint = critStopSprint,
            cooldownMode = mode19Enum,
            disableOnBlock = disableOnBlock,
            triggerOptions = triggerOptions
        ) }
        val input = ClickInput(
            player = player,
            target = null,
            attackCooldown = player.attackCooldownProgress,
            isFalling = player.motionY < 0.0 && !player.onGround
        )
        val result = strategy19.processTick(config, state19, input)
        applyResult19(result)
    }

    private fun applyResult19(result: ClickResult) {
        when (result) {
            is ClickResult.Click -> EventBridge.syntheticAttack = true
            is ClickResult.StopSprint -> EventBridge.setSprinting(false)
            is ClickResult.RestoreSprint -> EventBridge.setSprinting(result.wasSprinting)
            is ClickResult.Skip -> EventBridge.syntheticAttack = false
        }
    }

    // ========== Lifecycle ==========

    override fun onEnable() {
        state18.reset()
        state19.reset()
        strategy19.reset()
        EventBridge.syntheticAttackOverride = true

        tickListener = { player, target ->
            if (enabled) onClientTick(player, target)
        }
        EventBridge.registerTickListener(tickListener!!)
    }

    override fun onDisable() {
        tickListener?.let { EventBridge.unregisterTickListener(it) }
        tickListener = null
        EventBridge.syntheticAttack = false
        EventBridge.syntheticAttackOverride = false
        state18.reset()
        state19.reset()
    }
}
