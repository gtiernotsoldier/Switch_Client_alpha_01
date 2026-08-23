package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.util.Vec3
import kotlin.math.sqrt

/**
 * KeepSprint — no speed drop when attacking (Raven model).
 *
 * Vanilla MC reduces horizontal speed to ~60% when attacking. KeepSprint re-scales the player's
 * horizontal motion by a keep factor on each fresh attack so the speed doesn't drop.
 *
 * Trigger = a fresh attack (rising edge of physical left click OR AutoClicker's syntheticAttack).
 * On each fresh attack it multiplies motionX/Z by the keep factor via EventBridge.applyMotion
 * (same direct motion write as Velocity). No target/crosshair, no main-thread flag dance — just
 * the attack rising edge, matching Raven's KeepSprint.sl() which multiplies motion after the
 * attack.
 *
 * Config:
 *   - HorizontalKeep (Normal) / Legit distance interpolation, Chance.
 *   - OnlyGround/OnlyMove/etc. conditions.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ========== Speed ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Legit Mode: Distance-based interpolation ==========
    private val minReach by float("MinReach", 1.0f, 0f..1.5f, "blocks")
    private val maxReach by float("MaxReach", 3.0f, 2.5f..3.0f, "blocks")
    private val minKeep by float("MinKeep", 0.65f, 0.6f..0.7f)
    private val maxKeep by float("MaxKeep", 0.85f, 0.7f..0.95f)

    // ========== Probability ==========
    private val chance by probability("Chance", 100, 0..100)

    // ========== Unified Condition Engine ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@KeepSprint.onlyGround
        onlyMove = this@KeepSprint.onlyMove
        onlyMoveForward = this@KeepSprint.onlyMoveForward
        onlyWhenTargetGoesBack = this@KeepSprint.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    private var wasAttacking = false
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> if (enabled) onTick(p, t) }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // 'Attacking' = physical left click OR AutoClicker's synthetic attack.
        val attacking = EventBridge.syntheticAttack || player.isAttackKeyDown || EventBridge.isLeftMousePhysicallyDown

        // Trigger only on the fresh-attack rising edge (matches Raven: act once per attack).
        val freshAttack = attacking && !wasAttacking
        wasAttacking = attacking
        if (!freshAttack) return

        // Conditions (OnlyGround/OnlyMove/...). No target required.
        if (!ConditionChecker.check(triggerOptions, player, target)) return
        if (chance.current < 100 && kotlin.random.Random.nextInt(100) >= chance.current) return

        // Compute the keep factor (core algorithm) and apply the scaled motion directly.
        val keepFactor = when (mode) {
            "Legit" -> {
                val d = target?.distance
                val minR = minReach; val maxR = maxReach
                when {
                    d == null -> horizontalKeep
                    d <= minR -> minKeep
                    d >= maxR -> maxKeep
                    else -> minKeep + (maxKeep - minKeep) * (d - minR) / (maxR - minR)
                }
            }
            else -> horizontalKeep
        }

        val currentSpeed = sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ)
        if (currentSpeed < 0.001) return

        EventBridge.applyMotion(
            Vec3(player.motionX * keepFactor, player.motionY, player.motionZ * keepFactor)
        )
        io.switchlite.core.logging.CoreLogger.debug(
            "[KeepSprint] Kept speed at ${"%.0f".format(keepFactor * 100)}% (mode=$mode)"
        )
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        wasAttacking = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        wasAttacking = false
    }
}
