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

/**
 * TriggerBot Module — autonomous attack when crosshair is on target.
 *
 * Unlike AutoClicker (which requires the player to hold left-click),
 * TriggerBot autonomously attacks whenever a valid target is within range.
 *
 * Uses the same Core click strategies as AutoClicker:
 *   1.8 — ProbabilisticClickStrategy, 1.9+ — CooldownClickStrategy.
 *
 * Only config: distance range (0-3 blocks). No CPS/filter config needed.
 */
object TriggerBot : Module("TriggerBot", Category.COMBAT) {

    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))
    var attackCooldownProvider: (() -> Float) = { 1.0f }

    private val rangeMin by float("RangeMin", 0.0f, 0.0f..3.0f, "blocks")
    private val rangeMax by float("RangeMax", 3.0f, 0.0f..3.0f, "blocks")

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
                val config = ClickConfig(
                    minCps = 8, maxCps = 14,
                    clickMode = ClickMode.SINGLE,
                    mode = ClickOperatingMode.LEGIT,
                    disableOnBlock = true,
                    triggerOptions = io.switchlite.core.option.TriggerOptions(chance = 100)
                )
                val input = ClickInput(player = player, target = target)
                val result = strategy18.execute(config, state18, input)
                if (result is ClickResult.Click) EventBridge.triggerAttack()
            }
            "1.9+" -> {
                val config = CooldownClickConfig(
                    cooldownThreshold = 1.0f,
                    cooldownMode = CooldownClickMode.LEGIT,
                    disableOnBlock = true,
                    triggerOptions = io.switchlite.core.option.TriggerOptions(chance = 100)
                )
                val input = ClickInput(
                    player = player, target = null,
                    attackCooldown = attackCooldownProvider(),
                    isFalling = player.motionY < 0.0 && !player.onGround
                )
                val result = strategy19.processTick(config, state19, input)
                if (result is ClickResult.Click) EventBridge.triggerAttack()
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
        state18.reset()
        state19.reset()
    }
}
