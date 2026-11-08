package io.doppel.core.strategy.player

/**
 * SuperKnockback's phase machine — the **oracle**.
 *
 * The live path is `strategy::super_knockback` in `core-rs`;
 * `SuperKnockbackParityTest` replays the golden fixture through this object to
 * prove the two agree. Nothing in the shipped agent refers to it — `:core` is
 * not a dependency of any runtime module.
 *
 * The JVM keeps only judgements and platform I/O: the range window, the
 * unified gate, the trigger style (a one-tick attack pulse or an exact
 * `hurtTime` match), `player.isSprinting && serverSprintState`, the
 * `isMovingForward` check inside the force-sprint restore, and the C0B /
 * sprint-claim writes. Everything stateful lives here: the phase machine
 * (Idle / Delay / SprintTap), the delay deadline, the coordination offset, the
 * SprintTap three-tick force sequence, the per-target hit-count throttle and
 * the probability roll — on SuperKnockback's own RNG stream.
 *
 * One tick can produce two independent actions, so the returned code packs
 * the SprintTap step (low nibble) and the fire step (high nibble).
 *
 * Mirrors the original `SuperKnockback.onTick`:
 *  - the SprintTap force machine advances at the top of the tick (no return);
 *  - a delayed action fires at its deadline and short-circuits the tick;
 *  - a coordination offset fires on the next tick and short-circuits;
 *  - no target / trigger not met / range or gate failure → no hit counted;
 *  - target switch restarts the hit throttle;
 *  - the hit-count throttle consumes a hit before the chance roll;
 *  - chance roll at the fire point only, then delay or fire now.
 */
object SuperKnockbackLogic {

    enum class Phase(val code: Int) { IDLE(0), DELAY(1), SPRINT_TAP(2) }

    /** Wire mode codes, mirrored from `strategy::super_knockback`. */
    const val MODE_SPRINT_TAP = 0
    const val MODE_OLD = 1
    const val MODE_SNEAK_PACKET = 2

    data class State(
        val phase: Phase = Phase.IDLE,
        /** Deadline at which a delayed action starts. */
        val delayEndNs: Long = 0L,
        /** One-tick offset when coordinated with SprintReset. */
        val coordOffsetTicks: Int = 0,
        /** SprintTap force-sprint countdown (2 -> 1 -> 0). */
        val sprintTicks: Int = 0,
        /** Hits seen for the current target since the last fire. */
        val hitCounter: Int = 0,
        /** The target whose throttle is being counted. */
        val lastTargetId: Int = -1
    )

    /** The SprintTap force-sprint step at the top of the tick. */
    enum class SprintStep(val code: Int) { NONE(0), BREAK(1), RESTORE(2), END(3) }

    /** The fire step from the trigger evaluation. Codes match the wire. */
    enum class FireStep(val code: Int) {
        NONE(0), SPRINT_TAP(4), OLD(5), SNEAK_PACKET(6)
    }

    data class Input(
        val nowNs: Long,
        val hasTarget: Boolean,
        /** RisingEdge pulse or an exact hurtTime match, evaluated on the JVM. */
        val triggerOk: Boolean,
        val inRange: Boolean,
        val gateOk: Boolean,
        val entityId: Int,
        val chance: Int,
        val hitCount: Int,
        val delayMs: Int,
        val mode: Int,
        val sprintTapAllowed: Boolean,
        val coordinationActive: Boolean
    )

    data class Decision(val code: Int, val state: State)

    fun pack(sprint: SprintStep, fire: FireStep): Int =
        sprint.code or (fire.code shl 4)

    fun unpackSprint(code: Int): Int = code and 0xF
    fun unpackFire(code: Int): Int = (code shr 4) and 0xF

    /**
     * @param roll invoked **only** at the fire point and only when
     *        `chance < 100`, so idle / throttled / gated / delayed /
     *        coordinating ticks never advance the caller's randomness.
     */
    fun tick(state: State, input: Input, roll: () -> Int): Decision {
        var s = state
        var sprint = SprintStep.NONE

        // ---- SprintTap force-sprint machine (top of the tick) ----
        if (s.phase == Phase.SPRINT_TAP && input.mode == MODE_SPRINT_TAP) {
            when (s.sprintTicks) {
                2 -> {
                    s = s.copy(sprintTicks = 1)
                    sprint = SprintStep.BREAK
                }
                1 -> {
                    s = s.copy(sprintTicks = 0)
                    sprint = SprintStep.RESTORE
                }
                else -> {
                    s = s.copy(phase = Phase.IDLE)
                    sprint = SprintStep.END
                }
            }
        }

        // ---- Delay timer -> start action, then short-circuit ----
        if (s.phase == Phase.DELAY) {
            val (fire, next) = if (input.nowNs >= s.delayEndNs) {
                startAction(s.copy(phase = Phase.IDLE), input)
            } else {
                FireStep.NONE to s
            }
            return Decision(pack(sprint, fire), next)
        }

        // ---- Coordination offset: fire the deferred action from the last hit ----
        if (s.coordOffsetTicks > 0) {
            s = s.copy(coordOffsetTicks = s.coordOffsetTicks - 1)
            val (fire, next) = if (s.coordOffsetTicks == 0) {
                fireTrigger(s, input)
            } else {
                FireStep.NONE to s
            }
            return Decision(pack(sprint, fire), next)
        }

        // ---- Trigger evaluation ----
        if (!input.hasTarget) return Decision(pack(sprint, FireStep.NONE), s)

        if (s.lastTargetId != input.entityId) {
            s = s.copy(lastTargetId = input.entityId, hitCounter = 0)
        }

        if (!input.triggerOk) return Decision(pack(sprint, FireStep.NONE), s)
        if (!input.inRange || !input.gateOk) return Decision(pack(sprint, FireStep.NONE), s)

        s = s.copy(hitCounter = s.hitCounter + 1)
        if (s.hitCounter < input.hitCount) return Decision(pack(sprint, FireStep.NONE), s)
        s = s.copy(hitCounter = 0)

        // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100).
        if (input.chance < 100 && roll() >= input.chance) {
            return Decision(pack(sprint, FireStep.NONE), s)
        }

        // SprintReset acts this tick; offset one tick so our C0B burst lands
        // after its reset burst.
        if (input.coordinationActive) {
            s = s.copy(coordOffsetTicks = 1)
            return Decision(pack(sprint, FireStep.NONE), s)
        }

        val (fire, next) = fireTrigger(s, input)
        return Decision(pack(sprint, fire), next)
    }

    /** Delay or start now. `startAction` uses the *current* mode, so no mode is captured. */
    private fun fireTrigger(s: State, input: Input): Pair<FireStep, State> {
        if (input.delayMs > 0) {
            return FireStep.NONE to s.copy(
                phase = Phase.DELAY,
                delayEndNs = input.nowNs + input.delayMs * 1_000_000L
            )
        }
        return startAction(s, input)
    }

    private fun startAction(s: State, input: Input): Pair<FireStep, State> = when (input.mode) {
        MODE_OLD -> FireStep.OLD to s
        MODE_SNEAK_PACKET -> FireStep.SNEAK_PACKET to s
        // SprintTap only arms the force sequence when the server mirror says
        // we are sprinting. The HUD still flashes either way.
        else -> {
            val next = if (input.sprintTapAllowed) {
                s.copy(sprintTicks = 2, phase = Phase.SPRINT_TAP)
            } else {
                s
            }
            FireStep.SPRINT_TAP to next
        }
    }
}
