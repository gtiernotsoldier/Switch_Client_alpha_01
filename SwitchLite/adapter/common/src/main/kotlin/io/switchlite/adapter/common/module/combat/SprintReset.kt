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
 * SprintReset Module — packet-level sprint state reset for max knockback.
 *
 * Sends network packets (no key simulation) to reset sprint:
 *   **Nostop**: C0B stop sprinting + start sprinting (explicit server-side reset).
 *   **Silent**: C03 player position packet (implicit movement state refresh).
 *
 * Fires on the rising edge of the target's hurt animation (a fresh hit), so it triggers
 * reliably under 20Hz sampling.
 */
object SprintReset : Module("SprintReset", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Nostop", "Silent"))
    private val chance by int("Chance", 100, 0..100, "%")
    private val delay by int("Delay", 0, 0..500, "ms")
    private val tick by int("Tick", 1, 1..20)

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

    // ========== State ==========
    private var hitCounter: Int = 0
    private var sending: Boolean = false
    private var pendingMode: String = ""
    private var sendTimeNano: Long = 0L

    // Rising-edge "just hit" detection. The target's hurtTime (hurt animation) jumps > 0 the
    // instant it's hit and stays > 0 for ~10 ticks; between hits (i-frame window) it returns to
    // 0 for ~1s. So the 0 -> >0 transition reliably marks a fresh hit even under 20Hz sampling,
    // unlike the old exact `hurtResistantTime == 10` match (a razor-thin 1-tick window that the
    // background thread frequently misses).
    private var prevHurt = false
    private var lastTargetId: Int = -1

    // ========== Tick Listener ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // Process pending delayed send
        if (sending) {
            if (System.nanoTime() >= sendTimeNano) {
                EventBridge.sendSprintReset(pendingMode)
                sending = false
            }
            return
        }

        if (target == null) {
            // No target — reset hit-edge state so a new target's first hit is caught.
            prevHurt = false
            lastTargetId = -1
            return
        }

        // Reset edge state when switching targets.
        if (lastTargetId != target.entityId) {
            lastTargetId = target.entityId
            prevHurt = false
            hitCounter = 0
        }

        // Built-in 3-block range
        if (target.distance > 3.0f) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // Rising-edge "just hit": hurtTime just became > 0 (fresh hit). Reliable at 20Hz.
        val isHurt = target.hurtTime > 0
        val justHit = isHurt && !prevHurt
        prevHurt = isHurt
        if (!justHit) return

        // Tick throttle: fire once every `tick` hits.
        hitCounter++
        if (hitCounter < tick) return
        hitCounter = 0

        // Probability
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // Schedule send
        if (delay > 0) {
            sendTimeNano = System.nanoTime() + delay * 1_000_000L
            pendingMode = mode
            sending = true
        } else {
            EventBridge.sendSprintReset(mode)
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        hitCounter = 0
        prevHurt = false
        lastTargetId = -1
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        hitCounter = 0
        sending = false
        prevHurt = false
        lastTargetId = -1
    }
}
