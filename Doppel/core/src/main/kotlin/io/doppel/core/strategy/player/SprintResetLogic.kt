package io.doppel.core.strategy.player

/**
 * SprintReset's per-tick decision — the **oracle**.
 *
 * The live path is `strategy::sprint_reset` in `core-rs`;
 * `SprintResetParityTest` replays the golden fixture through this object to
 * prove the two agree. Nothing in the shipped agent refers to it — `:core` is
 * not a dependency of any runtime module.
 *
 * The module keeps only judgements (target present, distance window, unified
 * gate, hurt bit) and platform I/O. Everything stateful lives here: the
 * rising-edge bit, the hit-count throttle, the target id, and the delayed-send
 * deadline (including the chance roll).
 *
 * Mirrors the original `SprintReset.onTick`:
 *  - an armed send fires at its deadline and short-circuits the whole tick;
 *  - no target           → forget the edge, keep the hit counter;
 *  - target switch       → reset edge + throttle;
 *  - range / gate failure→ return WITHOUT touching the edge;
 *  - rising edge         → count a hit, throttle, roll the chance;
 *  - fire                → delayed (arm a deadline) or immediate send.
 */
object SprintResetLogic {

    data class State(
        val prevHurt: Boolean = false,
        val lastTargetId: Int = -1,
        val hitCounter: Int = 0,
        val sending: Boolean = false,
        val sendAtNs: Long = 0L,
        val pendingMode: Int = 0
    )

    data class Input(
        val nowNs: Long,
        val hasTarget: Boolean,
        val inRange: Boolean,
        val gateOk: Boolean,
        val isHurt: Boolean,
        val entityId: Int,
        val chance: Int,
        val hitCount: Int,
        val delayMs: Int,
        val mode: Int
    )

    /** 0 none, 1 + mode send. */
    enum class Step(val code: Int) {
        NONE(0), SEND_NOSTOP(1), SEND_SILENT(2)
    }

    data class Decision(val step: Step, val state: State)

    /**
     * @param roll invoked **only** at the fire point and only when
     *        `chance < 100`, so idle / throttled / gated ticks never advance
     *        the caller's randomness.
     */
    fun tick(state: State, input: Input, roll: () -> Int): Decision {
        var s = state

        // A send armed on an earlier tick takes priority and short-circuits.
        if (s.sending) {
            if (input.nowNs >= s.sendAtNs) {
                s = s.copy(sending = false)
                return Decision(sendStep(s.pendingMode), s)
            }
            return Decision(Step.NONE, s)
        }

        if (!input.hasTarget) {
            s = s.copy(prevHurt = false, lastTargetId = -1)
            return Decision(Step.NONE, s)
        }

        if (s.lastTargetId != input.entityId) {
            s = s.copy(lastTargetId = input.entityId, prevHurt = false, hitCounter = 0)
        }

        // Range / gate failures return early without consuming the edge.
        if (!input.inRange || !input.gateOk) {
            return Decision(Step.NONE, s)
        }

        val justHit = input.isHurt && !s.prevHurt
        s = s.copy(prevHurt = input.isHurt)
        if (!justHit) {
            return Decision(Step.NONE, s)
        }

        s = s.copy(hitCounter = s.hitCounter + 1)
        if (s.hitCounter < input.hitCount) {
            return Decision(Step.NONE, s)
        }
        s = s.copy(hitCounter = 0)

        // The only draw. Kotlin's `Random.nextInt(100)` is [0, 100).
        if (input.chance < 100 && roll() >= input.chance) {
            return Decision(Step.NONE, s)
        }

        if (input.delayMs > 0) {
            s = s.copy(
                sending = true,
                sendAtNs = input.nowNs + input.delayMs * 1_000_000L,
                pendingMode = input.mode
            )
            return Decision(Step.NONE, s)
        }

        return Decision(sendStep(input.mode), s)
    }

    private fun sendStep(mode: Int): Step = if (mode == 1) Step.SEND_SILENT else Step.SEND_NOSTOP
}
