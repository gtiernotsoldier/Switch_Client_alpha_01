package io.switchlite.adapter.common.module.combat

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.strategy.combat.CombatTrigger
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
 * Triggers on attack when target.hurtTime equals HurtTime threshold.
 * 1.8 exclusive — packet classes don't exist in 1.9+.
 */
object SprintReset : Module("SprintReset", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("Nostop", "Silent"))
    private val hurtTime by int("HurtTime", 10, 1..10)
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
    private var hitThreshold: Int = 1
    private var sending: Boolean = false
    private var pendingMode: String = ""
    private var sendTimeNano: Long = 0L

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

        if (target == null) return

        // Built-in 3-block range
        if (target.distance > 3.0f) return

        // Condition check
        if (!ConditionChecker.check(triggerOptions, player, target)) return

        // CombatTrigger EQUAL mode + attack counting
        val eval = CombatTrigger.evaluate(
            mode = CombatTrigger.Mode.EQUAL,
            target = target,
            maxHurtTime = hurtTime,
            hitCounter = hitCounter,
            hitThreshold = hitThreshold,
            hitPerMin = tick,
            hitPerMax = tick,
            chance = 100 // passthrough — probability checked below
        )
        hitCounter = eval.hitCounter
        hitThreshold = eval.hitThreshold
        if (!eval.fire) return

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
        hitThreshold = tick
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        hitCounter = 0
        sending = false
    }
}
