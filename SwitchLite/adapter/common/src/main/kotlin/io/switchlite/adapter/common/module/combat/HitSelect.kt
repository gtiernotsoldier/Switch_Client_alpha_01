package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.int
import io.switchlite.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * HitSelect Module — intelligent click filter for optimized DPS.
 *
 * Cancels ineffective attacks (target immune / cooldown not ready) so only
 * damaging hits pass through. Runs at START tick phase — before the game
 * processes input — to pre-emptively cancel clicks.
 *
 * Two modes:
 *   **Burst**: allow click when target can be damaged (1.8: hurtTime==0, 1.9+: cooldown ready).
 *   **Critical**: if moving upward → cancel; otherwise → Burst.
 *
 * 1.8: trigger on target.hurtTime == 0.         Tick cooldown default 8.
 * 1.9+: trigger on attack cooldown >= threshold. Tick cooldown default 12.
 */
object HitSelect : Module("HitSelect", Category.COMBAT) {

    // ========== Version ==========
    var combatVersion by choices("CombatVersion", arrayOf("1.8", "1.9+"))
    var attackCooldownProvider: (() -> Float) = { 1.0f }

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Burst", "Critical"))
    private val chance by int("Chance", 100, 0..100, "%")
    private val tick by int("Tick", 8, 1..20, "ticks")

    // ========== 1.9+ ==========
    private val cooldownThreshold by int("CooldownThreshold", 100, 0..100, "%")

    // ========== Conditions ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@HitSelect.onlyGround
        onlyCurrentView = onlyTargeting
        onlyMove = this@HitSelect.onlyMove
        onlyMoveForward = this@HitSelect.onlyMoveForward
        onlyWhenTargetGoesBack = this@HitSelect.onlyWhenTargetGoesBack
    }

    // ========== Cooldown ==========
    private var cooldown: Int = 0

    // ========== StartTick Listener ==========
    private val startListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onStartTick(p, t)
    }

    private fun onStartTick(player: PlayerState, target: TargetState?) {
        // Cooldown counter
        if (cooldown > 0) { cooldown--; return }

        // Must be physically clicking
        if (!EventBridge.isLeftMousePhysicallyDown) return

        // Target required
        if (target == null) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Critical mode: cancel if moving upward
        if (mode == "Critical" && player.motionY > 0.0) {
            cancel()
            return
        }

        // Burst evaluation
        val shouldFire = when (combatVersion) {
            "1.8" -> target.hurtTime == 0
            "1.9+" -> attackCooldownProvider() >= cooldownThreshold / 100f
            else -> false
        }

        if (!shouldFire) {
            cancel()
            return
        }

        // Probability — fail also enters cooldown to prevent tick-level spam
        if (chance < 100 && Random.nextInt(100) >= chance) {
            cooldown = tick
            return
        }

        // Allow click through, enter cooldown
        cooldown = tick
    }

    private fun cancel() {
        EventBridge.cancelAttack()
        cooldown = tick
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        cooldown = 0
        EventBridge.registerStartTickListener(startListener)
    }

    override fun onDisable() {
        EventBridge.unregisterStartTickListener(startListener)
        cooldown = 0
    }
}
