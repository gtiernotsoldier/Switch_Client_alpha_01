package io.doppel.adapter.common.module.combat

import io.doppel.core.condition.ConditionChecker
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions
import kotlin.random.Random

/**
 * SuperKnockback Module — sprint manipulation to maximise knockback dealt.
 *
 * Ported toward LiquidBounce's SuperKnockback semantics. Attack-event driven: fires on a fresh
 * hit of the crosshair target (hurtTime rising edge — our reliable 20Hz equivalent of LB's
 * AttackEvent). Three modes:
 *   **SprintTap**: on hit, briefly break sprint then re-engage (uses serverSprintState mirror,
 *     like LB's PostSprintUpdate state machine).
 *   **Old**: on hit, send STOP+START+STOP+START sprint packets and force local+server sprint on
 *     (LB "Old" — reported best).
 *   **SneakPacket**: on hit, interleave STOP_SPRINTING / START_SNEAKING / START_SPRINTING /
 *     STOP_SNEAKING packets (LB "SneakPacket").
 *
 * Trigger conditions via the unified engine + built-in 3-block range + probability + optional
 * delay. `minEnemyRotDiffToIgnore` is omitted because our TargetState does not expose the
 * enemy's yaw (default 180° is a no-op anyway).
 */
object SuperKnockback : Module("SuperKnockback", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("SprintTap", "Old", "SneakPacket"))
    private val chance by int("Chance", 100, 0..100, "%")
    private val delay by int("Delay", 0, 0..500, "ms")

    // ========== Trigger Mode (two detection styles, both preserved) ==========
    //   RisingEdge: reliable 20Hz "just got hit" (hurtTime 0 -> >0). Never misses.
    //   HurtTimeExact: fire when target.hurtTime equals the configured HurtTime value (default
    //   10). Stronger, matches LB's exact-match semantics, can be tuned — but like the old
    //   SuperKnockback/SprintReset bug, the exact ==N window can be missed under 20Hz sampling.
    private val triggerMode by choices("TriggerMode", arrayOf("RisingEdge", "HurtTimeExact"))
    private val hurtTime by int("HurtTime", 10, 0..10)

    // ========== Conditions (Unified Engine) ==========
    // Defaults follow LB: require moving forward to trigger (SprintTap needs forward motion).
    private val onlyGround by boolean("OnlyGround", false)
    private val onlyTargeting by boolean("OnlyTargeting", false)
    private val onlyMove by boolean("OnlyMove", true)
    private val onlyMoveForward by boolean("OnlyMoveForward", true)
    private val onlyWhenTargetGoesBack by boolean("OnlyWhenTargetGoesBack", false)

    private val triggerOptions by triggerOptions("Trigger") {
        onlyGround = this@SuperKnockback.onlyGround
        onlyCurrentView = onlyTargeting
        onlyMove = this@SuperKnockback.onlyMove
        onlyMoveForward = this@SuperKnockback.onlyMoveForward
        onlyWhenTargetGoesBack = this@SuperKnockback.onlyWhenTargetGoesBack
    }

    // ========== State ==========
    private enum class Phase { IDLE, DELAY, SPRINT_TAP }

    private var phase: Phase = Phase.IDLE
    private var delayEndNano: Long = 0L

    /** A fresh hit was detected this tick (attack listener -> evaluated in tick listener). */
    private var hitPending = false

    // SprintTap force-sprint state machine (LB PostSprintUpdate).
    private var sprintTicks = 0
    private var forceSprintState = 0

    /** One-tick offset countdown when coordinated with SprintReset. */
    private var coordOffsetTicks = 0

    private val attackListener: (TargetState?) -> Unit = {
        // Coordination: when SprintReset is also active, SprintReset acts first and we offset
        // our action by one tick so the two never fire a C0B burst in the same tick.
        if (enabled) hitPending = true
    }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t -> if (enabled) onTick(p, t) }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // ---- SprintTap force-sprint state machine (runs each tick while active) ----
        if (phase == Phase.SPRINT_TAP && mode == "SprintTap") {
            when (sprintTicks) {
                2 -> {
                    EventBridge.setSprinting(false)
                    forceSprintState = 2
                    sprintTicks--
                }
                1 -> {
                    if (player.isMovingForward) {
                        EventBridge.setSprinting(true)
                    }
                    forceSprintState = 1
                    sprintTicks--
                }
                else -> {
                    forceSprintState = 0
                    phase = Phase.IDLE
                }
            }
        }

        // ---- Delay timer -> start action ----
        if (phase == Phase.DELAY) {
            if (System.nanoTime() >= delayEndNano) {
                phase = Phase.IDLE
                startAction(player)
            }
            return
        }

        // ---- Coordination offset: fire the deferred action from the previous hit ----
        if (coordOffsetTicks > 0) {
            coordOffsetTicks--
            if (coordOffsetTicks == 0) {
                fireTrigger(player)
            }
            return
        }

        // ---- Trigger evaluation (both trigger styles preserved) ----
        val t = EventBridge.crosshairTarget ?: return

        when (triggerMode) {
            "RisingEdge" -> {
                // Fire on the fresh-hit rising edge (set by the attack listener).
                if (!hitPending) return
                hitPending = false
            }
            else -> { // "HurtTimeExact"
                // Fire when target.hurtTime equals the configured HurtTime value.
                // We must sample each tick (not rely on the edge listener).
                if (t.hurtTime != hurtTime) return
            }
        }

        if (!canTrigger(player, t)) return
        if (chance < 100 && Random.nextInt(100) >= chance) return

        // When SprintReset is also active it acts this tick; we offset one tick so our sprint
        // packets land after its reset burst (SprintReset first, SuperKnockback second).
        if (EventBridge.isSprintCoordinationActive()) {
            coordOffsetTicks = 1
            return
        }

        fireTrigger(player)
    }

    /** Perform the mode action (after condition/chance/delay/coordination gates). */
    private fun fireTrigger(player: PlayerState) {
        if (delay > 0) {
            delayEndNano = System.nanoTime() + delay * 1_000_000L
            phase = Phase.DELAY
        } else {
            startAction(player)
        }
    }

    /** Trigger gates: range, unified conditions. */
    private fun canTrigger(player: PlayerState, target: TargetState): Boolean {
        if (target.distance > 3.0f) return false
        return ConditionChecker.check(triggerOptions, player, target)
    }

    private fun startAction(player: PlayerState) {
        when (mode) {
            "SprintTap" -> {
                // LB: only break sprint if the server actually believes we're sprinting.
                if (player.isSprinting && EventBridge.serverSprintState) {
                    sprintTicks = 2
                    forceSprintState = 0
                    phase = Phase.SPRINT_TAP
                }
            }
            "Old" -> {
                if (player.isSprinting) {
                    EventBridge.sendEntityAction("STOP_SPRINTING")
                }
                EventBridge.sendEntityActions(
                    "START_SPRINTING", "STOP_SPRINTING", "START_SPRINTING"
                )
                // Force local + server sprint on.
                EventBridge.setSprinting(true)
                EventBridge.serverSprintState = true
            }
            "SneakPacket" -> {
                EventBridge.sendEntityActions(
                    "STOP_SPRINTING", "START_SNEAKING", "START_SPRINTING", "STOP_SNEAKING"
                )
            }
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerTickListener(tickListener)
        EventBridge.setSuperKnockbackActive(true)
        phase = Phase.IDLE
        hitPending = false
        sprintTicks = 0
        forceSprintState = 0
        coordOffsetTicks = 0
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.setSuperKnockbackActive(false)
        // Clean up stuck state — restore sprint.
        EventBridge.setSprinting(true)
        EventBridge.serverSprintState = true
        phase = Phase.IDLE
        hitPending = false
        sprintTicks = 0
        forceSprintState = 0
        coordOffsetTicks = 0
    }
}
