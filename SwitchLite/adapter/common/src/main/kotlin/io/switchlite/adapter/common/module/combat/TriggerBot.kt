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
import io.switchlite.core.strategy.click.ProbabilisticClickStrategy
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.option.int

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
    private val strategy18 = ProbabilisticClickStrategy()
    private val state18 = ClickStrategy.State()
    private val strategy19 = CooldownClickStrategy()
    private val state19 = CooldownClickStrategy.CritState()

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
                    triggerOptions = io.switchlite.core.option.TriggerOptions(chance = 100)
                ) }
                val input = ClickInput(player = player, target = target)
                val result = strategy18.execute(config, state18, input)
                if (result is ClickResult.Click) EventBridge.setSyntheticAttack(true)
                else EventBridge.setSyntheticAttack(false)
            }
            "1.9+" -> {
                val config = cachedConfig("19") { CooldownClickConfig(
                    cooldownThreshold = cooldownThreshold / 100f,
                    cooldownMode = CooldownClickMode.LEGIT,
                    disableOnBlock = true,
                    triggerOptions = io.switchlite.core.option.TriggerOptions(chance = 100)
                ) }
                val input = ClickInput(
                    player = player, target = null,
                    attackCooldown = player.attackCooldownProgress,
                    isFalling = player.motionY < 0.0 && !player.onGround
                )
                val result = strategy19.processTick(config, state19, input)
                if (result is ClickResult.Click) EventBridge.setSyntheticAttack(true)
                else EventBridge.setSyntheticAttack(false)
            }
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        state18.reset()
        state19.reset()
        strategy19.reset()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.setSyntheticAttack(false)
        state18.reset()
        state19.reset()
    }
}
