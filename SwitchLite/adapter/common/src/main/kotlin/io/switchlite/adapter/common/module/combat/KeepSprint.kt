package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import kotlin.random.Random

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

    // ========== Trigger (delay + hit throttle) ==========
    private val delay by int("Delay", 0, 0..500, "ms")
    private val hitCount by int("HitCount", 1, 1..20, "hits")

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
    @Volatile private var lastPlayer: PlayerState? = null
    private var hitCounter = 0
    private var delayEndNano = 0L
    private var delayPending = false
    private var wasAttacking = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> if (enabled) onTick(p, t) }

    private fun onTick(player: PlayerState, target: TargetState?) {
        lastPlayer = player

        // Apply delayed keep when the timer expires.
        if (delayPending) {
            if (System.nanoTime() >= delayEndNano) {
                delayPending = false
                applyKeep(player, target)
            }
            return
        }

        // Trigger = the player is holding the attack (physical left click / attack key down).
        // KeepSprint does NOT need a target; only the player's own attack action matters.
        val attacking = player.isAttackKeyDown || EventBridge.isLeftMousePhysicallyDown

        // Hit rising edge of the player's own attack (to throttle "per hit").
        val attackStarted = attacking && !wasAttacking
        wasAttacking = attacking

        if (!attacking) {
            hitCounter = 0
            return
        }

        // Only count a fresh attack for the hit-count throttle.
        if (!attackStarted) return

        // Unified conditions (OnlyGround/OnlyMove/...). Note: no target required.
        if (!ConditionChecker.check(triggerOptions, player, target)) return
        if (chance.current < 100 && Random.nextInt(100) >= chance.current) return

        // Hit-count throttle: keep once every `hitCount` hits.
        hitCounter++
        if (hitCounter < hitCount) return
        hitCounter = 0

        // Delay or immediate keep.
        if (delay > 0) {
            delayEndNano = System.nanoTime() + delay * 1_000_000L
            delayPending = true
        } else {
            applyKeep(player, target)
        }
    }

    /** Arm the keep-speed application. The actual motion is applied on the MC main thread. */
    private fun applyKeep(player: PlayerState, target: TargetState?) {
        if (player === io.switchlite.core.model.PlayerState.EMPTY) return
        if (!ConditionChecker.check(triggerOptions, player, target)) return

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
        EventBridge.armKeepSprint(result.keepFactor)
        io.switchlite.core.logging.CoreLogger.debug(
            "[KeepSprint] Armed keep at ${"%.0f".format(result.keepFactor * 100)}% (mode=$mode)"
        )
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
        lastPlayer = null
        hitCounter = 0
        delayPending = false
        wasAttacking = false
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        lastPlayer = null
        hitCounter = 0
        delayPending = false
        wasAttacking = false
    }
}
