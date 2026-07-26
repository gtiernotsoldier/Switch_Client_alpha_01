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
 * SuperKnockback Module — sprint manipulation to maximise knockback dealt.
 *
 * Two modes:
 *   **SprintTap**: briefly disable sprint on hit, then re-enable next tick.
 *   **SprintTap2**: press S (back) for [stopTicks] to force a movement stop,
 *     release, wait [unSprintTicks], then restore sprint.
 *
 * Triggers when target.hurtTime == [hurtTime] (default 10 = the instant the
 * target was hit), subject to ConditionChecker, probability, and built-in
 * 3-block range. Optional delay before execution.
 */
object SuperKnockback : Module("SuperKnockback", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("SprintTap", "SprintTap2"))
    private val hurtTime by int("HurtTime", 10, 0..10)
    private val chance by int("Chance", 100, 0..100, "%")
    private val delay by int("Delay", 0, 0..500, "ms")

    // ========== SprintTap2 Config ==========
    private val stopTicks by int("StopTicks", 1, 1..5, "ticks")
    private val unSprintTicks by int("UnSprintTicks", 2, 1..5, "ticks")

    // ========== Conditions (Unified Engine) ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@SuperKnockback.onlyGround
        onlyCurrentView = onlyTargeting
        onlyMove = this@SuperKnockback.onlyMove
        onlyMoveForward = this@SuperKnockback.onlyMoveForward
        onlyWhenTargetGoesBack = this@SuperKnockback.onlyWhenTargetGoesBack
    }

    // ========== State Machine ==========
    private enum class Phase { IDLE, DELAY, SPRINT_OFF, STOP, RECOVER }

    private var phase: Phase = Phase.IDLE
    private var delayEndNano: Long = 0L
    private var phaseTicksRemaining: Int = 0

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // ---- Active phases (non-IDLE) ----
        when (phase) {
            Phase.DELAY -> {
                if (System.nanoTime() >= delayEndNano) {
                    startAction()
                }
                return
            }
            Phase.SPRINT_OFF -> {
                // 1-tick sprint off → re-enable (if moving forward)
                if (player.isMovingForward) {
                    EventBridge.setSprinting(true)
                }
                phase = Phase.IDLE
                return
            }
            Phase.STOP -> {
                // Holding S — wait stopTicks
                phaseTicksRemaining--
                if (phaseTicksRemaining <= 0) {
                    EventBridge.releaseBack()
                    phase = Phase.RECOVER
                    phaseTicksRemaining = unSprintTicks
                }
                return
            }
            Phase.RECOVER -> {
                // Recovering — wait unSprintTicks, then re-sprint
                phaseTicksRemaining--
                if (phaseTicksRemaining <= 0) {
                    if (player.isMovingForward) {
                        EventBridge.setSprinting(true)
                    }
                    phase = Phase.IDLE
                }
                return
            }
            Phase.IDLE -> { /* evaluate below */ }
        }

        // ---- IDLE: evaluate trigger ----

        // Target required
        if (target == null) return

        // Built-in: 3-block range
        if (target.distance > 3.0f) return

        // HurtTime trigger
        if (target.hurtTime != hurtTime) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // Delay or immediate
        if (delay > 0) {
            delayEndNano = System.nanoTime() + delay * 1_000_000L
            phase = Phase.DELAY
        } else {
            startAction()
        }
    }

    private fun startAction() {
        when (mode) {
            "SprintTap" -> {
                EventBridge.setSprinting(false)
                phase = Phase.SPRINT_OFF
            }
            "SprintTap2" -> {
                EventBridge.pressBack()
                phase = Phase.STOP
                phaseTicksRemaining = stopTicks.coerceAtMost(unSprintTicks)
            }
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Clean up stuck keys
        if (phase == Phase.STOP) {
            EventBridge.releaseBack()
        }
        if (phase == Phase.SPRINT_OFF || phase == Phase.RECOVER) {
            EventBridge.setSprinting(true)
        }
        phase = Phase.IDLE
    }
}
