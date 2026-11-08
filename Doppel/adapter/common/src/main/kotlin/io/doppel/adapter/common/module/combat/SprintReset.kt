package io.doppel.adapter.common.module.combat

import io.doppel.ipc.Strategies
import io.doppel.ipc.SprintResetStep
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions

/**
 * SprintReset Module — packet-level sprint state reset for max knockback.
 *
 * Sends network packets (no key simulation) to reset sprint:
 *   **Nostop**: C0B stop sprinting + start sprinting (explicit server-side reset).
 *   **Silent**: C03 player position packet (implicit movement state refresh).
 *
 * This side holds only **logic judgements + marshalling + platform I/O**:
 * whether a target exists, whether it is inside the distance window, the
 * unified gate, and the hurt bit. Everything stateful — the rising-edge bit,
 * the hit-count throttle, the per-target cycle, the delay window and the
 * probability roll — lives in the algorithm process. Each tick we marshal the
 * judgements, the clock value and the tuning knobs into one request; the core
 * returns a step, and the `when (step)` below turns it into the one platform
 * write this module owns. No `kotlin.random`, no local time-window judgement
 * (`nanoTime()` is an input we marshal, like Backtrack/Reach), no cross-tick
 * counters.
 */
object SprintReset : Module("SprintReset", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Nostop", "Silent"))
    private val chance by int("Chance", 100, 0..100, "%")
    private val delay by int("Delay", 0, 0..500, "ms")
    private val hitCount by int("HitCount", 1, 1..20, "hits")

    /**
     * Distance window. Evaluated on the frame a hit lands, when the crosshair
     * target is already inside reach; lowering RangeMin is what disables the
     * module. This is a judgement the JVM keeps; the core only sees its result.
     */
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 6.0f, 0.0f..6.0f, "blocks")

    // ========== Conditions (Unified Engine) ==========
    private val onlyGround by boolean("OnlyGround", true)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", false)
    private val onlyMoveForward by boolean("OnlyMoveForward", false)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@SprintReset.onlyGround
        onlyCurrentView = onlyTargeting
        onlyMove = this@SprintReset.onlyMove
        onlyMoveForward = this@SprintReset.onlyMoveForward
        onlyWhenTargetGoesBack = this@SprintReset.onlyWhenTargetGoesBack
    }

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // ---- Judgements (this side decides nothing beyond these predicates) ----
        val hasTarget = target != null
        val inRange = target != null && target.distance >= rangeMin && target.distance <= rangeMax
        val gateOk = hasTarget && inRange && Strategies.gate(player, target, triggerOptions)
        val isHurt = target != null && target.hurtTime > 0
        val entityId = target?.entityId ?: -1

        val step = Strategies.sprintReset(
            player, target, System.nanoTime(),
            hasTarget, inRange, gateOk, isHurt, entityId,
            chance, hitCount, delay, modeCode()
        )

        // ---- Platform side effect: land the step the core decided ----
        when (step) {
            SprintResetStep.SEND_NOSTOP -> {
                EventBridge.sendSprintReset("Nostop")
                markWorked() // the HUD row flashes on the reset actually sent
            }
            SprintResetStep.SEND_SILENT -> {
                EventBridge.sendSprintReset("Silent")
                markWorked()
            }
            // NONE / null: no answer from the core — send nothing. Inventing a
            // packet burst without being told to is the one thing this module
            // must never do by accident.
            else -> Unit
        }
    }

    /** Wire mode code shared with the core's step encoding. */
    private fun modeCode(): Int = if (mode == "Silent") 1 else 0

    // ========== Lifecycle ==========
    override fun onEnable() {
        // A fresh enable must not inherit a rising-edge / throttle / delayed
        // send the core still believes it owns.
        Strategies.resetPlayer()
        EventBridge.setSprintResetActive(true)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.setSprintResetActive(false)
        Strategies.resetPlayer()
    }
}
