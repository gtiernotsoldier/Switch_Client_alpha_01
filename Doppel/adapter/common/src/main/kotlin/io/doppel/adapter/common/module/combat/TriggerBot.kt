package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.option.ClickMode
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickInput
import io.doppel.contract.strategy.click.ClickOperatingMode
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.ClickStrategy
import io.doppel.contract.strategy.click.CooldownClickConfig
import io.doppel.contract.strategy.click.CooldownClickMode
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.contract.option.TriggerOptions
import io.doppel.contract.strategy.click.CritState

/**
 * TriggerBot Module — autonomous attack when crosshair is on target.
 *
 * Unlike AutoClicker (which requires the player to hold left-click),
 * TriggerBot autonomously attacks whenever a valid target is within range.
 *
 * **Primary path: 1.9+** — the cooldown bar makes timing the critical factor;
 * CooldownClickStrategy ensures every attack fires at full damage.
 * 1.8 path is provided for compatibility but offers less benefit (CPS
 * is tick-bound and equivalent to manual clicking).
 *
 * **By design, no condition filters** — no weapon check, no OnlyGround, etc.
 * Pair with HitSelect to filter ineffective clicks and AimAssist for targeting.
 *
 * Uses the same Core click strategies as AutoClicker:
 *   1.8 — ProbabilisticClickStrategy, 1.9+ — CooldownClickStrategy.
 *
 * Only config: distance range (0-3 blocks). No CPS/filter config needed.
 */
object TriggerBot : Module("TriggerBot", Category.COMBAT) {

    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))

    private val rangeMin by float("RangeMin", 0.0f, 0.0f..3.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..3.0f, "blocks")

    // ========== 1.8 CPS ==========
    private val minCps by int("MinCPS", 8, 1..20, "cps")
    private val maxCps by int("MaxCPS", 14, 1..20, "cps")

    // ========== 1.9+ Cooldown ==========
    private val cooldownThreshold by int("CooldownThreshold", 100, 50..100, "%")

    // ========== Core Strategies ==========
    private val strategy18: ClickStrategy<ClickConfig, ClickStrategy.State> = io.doppel.ipc.Strategies.click()
    private val state18 = ClickStrategy.State()
    private val strategy19 = io.doppel.ipc.Strategies.cooldownClick()
    private val state19 = CritState()

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        if (target == null) return
        if (target.distance < rangeMin || target.distance > rangeMax) return

        when (combatVersion) {
            "1.8" -> {
                val config = cachedConfig("18") { ClickConfig(
                    minCps = minCps, maxCps = maxCps,
                    clickMode = ClickMode.SINGLE,
                    mode = ClickOperatingMode.LEGIT,
                    disableOnBlock = true,
                    triggerOptions = io.doppel.contract.option.TriggerOptions(chance = 100)
                ) }
                val input = ClickInput(player = player, target = target)
                val result = strategy18.execute(config, state18, input)
                if (result is ClickResult.Click) {
                    EventBridge.syntheticAttack = true
                    markWorked() // the HUD row flashes while the bot is actually clicking
                } else EventBridge.syntheticAttack = false
            }
            "1.9+" -> {
                val config = cachedConfig("19") { CooldownClickConfig(
                    cooldownThreshold = cooldownThreshold / 100f,
                    cooldownMode = CooldownClickMode.LEGIT,
                    disableOnBlock = true,
                    triggerOptions = io.doppel.contract.option.TriggerOptions(chance = 100)
                ) }
                val input = ClickInput(
                    player = player, target = null,
                    attackCooldown = player.attackCooldownProgress,
                    isFalling = player.motionY < 0.0 && !player.onGround
                )
                val result = strategy19.processTick(config, state19, input)
                if (result is ClickResult.Click) {
                    EventBridge.syntheticAttack = true
                    markWorked()
                } else EventBridge.syntheticAttack = false
            }
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        state18.reset()
        state19.reset()
        strategy19.reset()
        EventBridge.syntheticAttackOverride = true
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.syntheticAttack = false
        EventBridge.syntheticAttackOverride = false
        state18.reset()
        state19.reset()
    }
}
