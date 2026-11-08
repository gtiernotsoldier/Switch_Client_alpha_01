package io.doppel.core.strategy.player

/**
 * LagRange's packet-hold machine — the **oracle**.
 *
 * The live path is `strategy::lag_range` in `core-rs`;
 * `LagRangeParityTest` replays the golden fixture through this object to
 * prove the two agree. Nothing in the shipped agent refers to it — `:core`
 * is not a dependency of any runtime module.
 *
 * The JVM keeps only judgements and platform I/O: the GUI-open check, the
 * target's distance sample, the weapon being a sword, and the single
 * `packetLagActive` write. Everything stateful lives here: the previous
 * distance, the damage edge, the sprint and block edges, and the hold's
 * start timestamp.
 *
 * Mirrors the original `LagRange.onTick` branch for branch — every early
 * return flushed *and* still updated the edge trackers it had touched, so
 * the order is part of the behaviour: which edge fires on the tick after a
 * flush depends on it.
 *
 * `dist` and `range` arrive as Double on the wire but both were Float on
 * the JVM, so the machine narrows them back and compares in Float — the
 * same conversion the Rust side does, and the one a bit-exact replay needs.
 *
 * There is no randomness — no roll parameter, no RNG stream.
 */
object LagRangeLogic {

    data class State(
        /** Previous tick's distance; -1 when there is nothing to compare against. */
        val lastDist: Float = -1f,
        /** Previous tick's hurt time; a *rise* is a fresh damage event. */
        val lastSelfHurt: Int = 0,
        /** Previous tick's sprint flag (only tracked while `sprintResetOn`). */
        val lastSprint: Boolean = false,
        /** Previous tick's blocking flag (only tracked while `blockSwordOn`). */
        val lastBlocking: Boolean = false,
        /** When the current hold started; 0 when not holding. */
        val holdStartNs: Long = 0L
    )

    /** What the module should write. Codes match the wire. */
    enum class Step(val code: Int) {
        NONE(0), HOLD(1), ARM(2), FLUSH(3)
    }

    data class Input(
        val nowNs: Long,
        val guiOpen: Boolean,
        val hasDist: Boolean,
        val dist: Double,
        val range: Double,
        val maxDelayMs: Int,
        val sprintResetOn: Boolean,
        val isSprinting: Boolean,
        val blockSwordOn: Boolean,
        val isBlocking: Boolean,
        val holdingWeaponOn: Boolean,
        val weaponIsSword: Boolean,
        val hurtTime: Int
    )

    data class Decision(val code: Int, val state: State)

    fun tick(state: State, input: Input): Decision {
        var s = state
        val dist = input.dist.toFloat()
        val range = input.range.toFloat()

        // ---- Gate: GUI open, no target, or out of range ----
        if (input.guiOpen || !input.hasDist || dist > range) {
            s = s.copy(holdStartNs = 0L, lastDist = -1f)
            return Decision(Step.FLUSH.code, s)
        }

        // ---- Damage taken: never held through it ----
        if (input.hurtTime > s.lastSelfHurt) {
            s = s.copy(lastSelfHurt = input.hurtTime, holdStartNs = 0L, lastDist = dist)
            return Decision(Step.FLUSH.code, s)
        }
        s = s.copy(lastSelfHurt = input.hurtTime)

        // ---- A sprint starting ----
        if (input.sprintResetOn) {
            if (input.isSprinting && !s.lastSprint) {
                s = s.copy(lastSprint = true, holdStartNs = 0L, lastDist = dist)
                return Decision(Step.FLUSH.code, s)
            }
            s = s.copy(lastSprint = input.isSprinting)
        }

        // ---- A block starting ----
        if (input.blockSwordOn) {
            if (input.isBlocking && !s.lastBlocking) {
                s = s.copy(lastBlocking = true, holdStartNs = 0L, lastDist = dist)
                return Decision(Step.FLUSH.code, s)
            }
            s = s.copy(lastBlocking = input.isBlocking)
        }

        // ---- Weapon dropped ----
        if (input.holdingWeaponOn && !input.weaponIsSword) {
            s = s.copy(holdStartNs = 0L, lastDist = dist)
            return Decision(Step.FLUSH.code, s)
        }

        // ---- Not closing in any more ----
        if (s.lastDist >= 0f && dist >= s.lastDist) {
            s = s.copy(holdStartNs = 0L, lastDist = dist)
            return Decision(Step.FLUSH.code, s)
        }
        s = s.copy(lastDist = dist)

        // ---- Closing in: arm or extend the hold ----
        val step = if (s.holdStartNs == 0L) {
            s = s.copy(holdStartNs = input.nowNs)
            Step.ARM
        } else {
            Step.HOLD
        }

        // Bounded even if nothing above fired. The original armed/extended
        // first and then checked the deadline, so the deadline tick's net
        // write is "lag off".
        if (input.nowNs - s.holdStartNs >= input.maxDelayMs * 1_000_000L) {
            s = s.copy(holdStartNs = 0L)
            return Decision(Step.FLUSH.code, s)
        }

        return Decision(step.code, s)
    }
}
