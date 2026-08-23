package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * KeepSprint — keeps sprint speed while attacking.
 *
 * Vanilla MC reduces horizontal speed when attacking (to ~60%). KeepSprint re-applies the
 * desired keep percentage so your speed doesn't drop while you fight.
 *
 * IMPORTANT: this module does NOT depend on any target/crosshair. It only looks at the PLAYER's
 * own attack action (holding left-click / attack key). That is the Raven model — Raven's
 * KeepSprint.multiplies motion when the player attacks, with no target requirement. Basing the
 * trigger on the crosshair target's hurtTime was the bug: if the crosshair wasn't precisely on
 * the mob the module never fired.
 *
 * While the player is attacking, on each qualifying hit it scales the player's horizontal motion
 * by the keep factor (applied on the main thread via EventBridge.armKeepSprint so it isn't
 * overwritten next frame).
 *
 * Config:
 *   - HorizontalKeep (Normal mode) / Legit distance interpolation.
 *   - Delay (ms before the keep applies), HitCount (fire once every N hits), Chance.
 *   - Trigger is the player's own attack; `OnlyMove`/`OnlyGround` conditions still apply.
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
    private var diagCount = 0
    private var setLogCount = 0
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> if (enabled) onTick(p, t) }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // Brute-force Raven model: every tick, if the player is attacking, keep the speed.
        // 'Attacking' = physical left click OR AutoClicker's synthetic attack (AutoClicker
        // drives syntheticAttack on the main thread; it does NOT change the physical button, so
        // we must include syntheticAttack or KeepSprint never fires under AutoClicker).
        val attacking = EventBridge.syntheticAttack || player.isAttackKeyDown || EventBridge.isLeftMousePhysicallyDown

        // Module-level throttled diagnostic (confirm the trigger signal and setKeepSprint calls).
        if (++diagCount % 40 == 0) {
            io.switchlite.core.logging.CoreLogger.info(
                "[KeepSprint] tick attacking=$attacking physL=${EventBridge.isLeftMousePhysicallyDown} " +
                "keyDown=${player.isAttackKeyDown} onGround=${player.onGround} sprint=${player.isSprinting} " +
                "cond=${ConditionChecker.check(triggerOptions, player, target)} keepActive=${EventBridge.isKeepSprintActive()}"
            )
        }

        if (!attacking) {
            EventBridge.clearKeepSprint()
            return
        }

        // Unified conditions (OnlyGround/OnlyMove/...). No target required.
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Compute the keep factor (core algorithm) and arm continuous keep.
        val config = io.switchlite.core.strategy.keepsprint.KeepSprintConfig(
            mode = mode,
            horizontalKeep = horizontalKeep,
            minReach = minReach,
            maxReach = maxReach,
            minKeep = minKeep,
            maxKeep = maxKeep,
            chance = chance.current,
            hurtTimeMax = 10,
            delayTicks = 0,
            cooldownTicks = 0
        )
        val result = io.switchlite.core.strategy.keepsprint.KeepSprintStrategy.computeKeepMotion(
            config, mode, target?.distance,
            player.motionX, player.motionY, player.motionZ
        )
        EventBridge.setKeepSprint(result.keepFactor)
        if (++setLogCount % 40 == 0) {
            io.switchlite.core.logging.CoreLogger.info(
                "[KeepSprint] setKeepSprint called factor=${result.keepFactor} keepActive=${EventBridge.isKeepSprintActive()}"
            )
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.clearKeepSprint()
    }
}
