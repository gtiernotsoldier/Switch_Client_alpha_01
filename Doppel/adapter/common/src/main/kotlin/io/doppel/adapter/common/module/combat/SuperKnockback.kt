package io.doppel.adapter.common.module.combat

import io.doppel.ipc.Strategies
import io.doppel.ipc.SuperKnockbackFireStep
import io.doppel.ipc.SuperKnockbackSprintStep
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
 * SuperKnockback Module — sprint manipulation to maximise knockback dealt.
 *
 * Ported toward LiquidBounce's SuperKnockback semantics. Attack-event driven:
 * fires on a fresh hit of the crosshair target. Three modes:
 *   **SprintTap**: on hit, briefly break sprint then re-engage (server sprint
 *     mirror + an OWNED sprint claim, like LB's PostSprintUpdate state machine).
 *   **Old**: on hit, send STOP+START+STOP+START sprint packets and force sprint on.
 *   **SneakPacket**: on hit, interleave four STOP/START sprint+sneak packets.
 *
 * This side holds only **logic judgements + marshalling + platform I/O**:
 *  - the crosshair target, the trigger style (a one-tick attack pulse or an
 *    exact `hurtTime` match), the distance window and the unified gate;
 *  - `player.isSprinting && serverSprintState`, the `isMovingForward` check
 *    inside the force-sprint restore, the sprint coordination flag;
 *  - and the C0B / sprint-claim platform writes.
 *
 * Everything stateful lives in the algorithm process: the Idle / Delay /
 * SprintTap phase machine, the delay deadline, the coordination offset, the
 * SprintTap force sequence, the per-target hit-count throttle and the
 * probability roll. No `kotlin.random`, no local time-window judgement
 * (`System.nanoTime()` is an input we marshal), no cross-tick decision state —
 * the only retained pulse is the one-tick attack notification, a platform
 * concern exactly like HitSelect's click pulse.
 */
object SuperKnockback : Module("SuperKnockback", Category.COMBAT) {

    // ========== Core ==========
    private val mode by choices("Mode", arrayOf("SprintTap", "Old", "SneakPacket"))
    private val chance by int("Chance", 100, 0..100, "%")
    private val delay by int("Delay", 0, 0..500, "ms")

    /** Fire once every N fresh hits (1 = every hit). Same shape as SprintReset's HitCount. */
    private val hitCount by int("HitCount", 1, 1..20, "hits")

    /**
     * Distance window, the same shape as WTap/STap's Range options. Evaluated
     * on the frame a hit lands; lowering RangeMin is what would disable the
     * module, since a min above reach can never be satisfied.
     */
    private val rangeMin by float("RangeMin", 0.0f, 0.0f..6.0f, "blocks")
    private val rangeMax by float("RangeMax", 6.0f, 0.0f..6.0f, "blocks")

    // ========== Trigger Mode (two detection styles, both preserved) ==========
    //   RisingEdge: reliable 20Hz "just got hit" (one-tick attack pulse).
    //   HurtTimeExact: fire when target.hurtTime equals the configured value.
    private val triggerMode by choices("TriggerMode", arrayOf("RisingEdge", "HurtTimeExact"))
    private val hurtTime by int("HurtTime", 10, 0..10)

    // ========== Conditions (Unified Engine) ==========
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

    // ========== Platform pulse (not a decision state) ==========
    /** Set by the attack listener, consumed as an input on the next tick. */
    @Volatile private var attackPulse = false

    /**
     * OWNED sprint claim — SprintTap works by the sprint state being *forced*,
     * not merely requested, and it has to beat Sprint (movement), which asks
     * for sprinting every tick you hold W.
     */
    private fun ownSprint(value: Boolean) = EventBridge.claimSprint(
        name, value, EventBridge.SprintClaimMode.OWNED, EventBridge.SPRINT_PRIORITY_TAP
    )

    private val attackListener: (TargetState?) -> Unit = { if (enabled) attackPulse = true }
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        // ---- Judgements (this side decides nothing beyond these predicates) ----
        val pulse = attackPulse
        attackPulse = false
        val t = EventBridge.crosshairTarget
        val hasTarget = t != null
        val triggerOk = if (triggerMode == "RisingEdge") pulse else (t?.hurtTime == hurtTime)
        val inRange = t != null && t.distance >= rangeMin && t.distance <= rangeMax
        // Short-circuit: a failed range never asks the unified gate, exactly
        // as the original canTrigger did.
        val gateOk = inRange && Strategies.gate(player, t, triggerOptions)
        val entityId = t?.entityId ?: -1
        val sprintTapAllowed = player.isSprinting && EventBridge.serverSprintState
        val coordinationActive = EventBridge.isSprintCoordinationActive()

        val step = Strategies.superKnockback(
            player, t, System.nanoTime(),
            hasTarget, triggerOk, inRange, gateOk, entityId,
            chance, hitCount, delay, modeCode(), sprintTapAllowed, coordinationActive
        ) ?: return // no answer: write nothing at all

        // ---- Platform side effect 1: the SprintTap force sequence ----
        when (step.sprint) {
            SuperKnockbackSprintStep.BREAK -> ownSprint(false)
            SuperKnockbackSprintStep.RESTORE ->
                if (player.isMovingForward) ownSprint(true)
            SuperKnockbackSprintStep.END -> EventBridge.releaseSprint(name)
            else -> Unit
        }

        // ---- Platform side effect 2: the fire step ----
        when (step.fire) {
            SuperKnockbackFireStep.SPRINT_TAP -> {
                // The core already armed the force sequence; only the HUD flash
                // is ours. Even when the server mirror disallowed the tap the
                // original startAction flashed on the triggering hit.
                markWorked()
            }
            SuperKnockbackFireStep.OLD -> {
                markWorked()
                if (player.isSprinting) EventBridge.sendEntityAction("STOP_SPRINTING")
                EventBridge.sendEntityActions(
                    "START_SPRINTING", "STOP_SPRINTING", "START_SPRINTING"
                )
                ownSprint(true)
            }
            SuperKnockbackFireStep.SNEAK_PACKET -> {
                markWorked()
                EventBridge.sendEntityActions(
                    "STOP_SPRINTING", "START_SNEAKING",
                    "START_SPRINTING", "STOP_SNEAKING"
                )
            }
            else -> Unit
        }
    }

    /** Wire mode code shared with the core's step encoding. */
    private fun modeCode(): Int = when (mode) {
        "Old" -> 1
        "SneakPacket" -> 2
        else -> 0 // SprintTap
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        // A fresh enable must not inherit a phase, a delay deadline, a
        // coordination offset, a force sequence or a hit throttle the core
        // still believes it owns.
        Strategies.resetPlayer()
        attackPulse = false
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerTickListener(tickListener)
        EventBridge.setSuperKnockbackActive(true)
    }

    override fun onDisable() {
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.setSuperKnockbackActive(false)
        attackPulse = false
        Strategies.resetPlayer()
        // The claim is released by Module.disable() -> releaseAllClaims, which
        // hands the sprint decision back to whatever else wants it.
    }
}
