package io.doppel.core.strategy.player

/**
 * HitSelect — the Kotlin **oracle** for `strategy::hit_select`.
 *
 * The ship path is the Rust port; `HitSelectParityTest` replays the golden
 * fixture through this object. Nothing shipped refers to it.
 *
 * The two halves share one [State]: the tick opens the wait-first / burst
 * windows and the gate reads them. A fixture that replayed each half in
 * isolation would let a version with two separate states pass, so the golden
 * stream interleaves them.
 */
object HitSelectLogic {

    class State {
        var trackedTargetId: Int = -1
        var targetFirstSeenNs: Long = -1L
        var lastConfirmedHitNs: Long = -1L
        var prevHurtTime: Int = 0
        var canCritNow: Boolean = false
        var lastCounterNs: Long = 0L
    }

    class TickInput(
        val nowNs: Long,
        val canCrit: Boolean,
        val playerHurtTime: Int,
        val leftMouseDown: Boolean,
        val hasTarget: Boolean,
        val targetId: Int,
        val targetHurtTime: Int,
        val counterHit: Boolean,
        val counterCdMs: Int
    )

    class GateInput(
        val nowNs: Long,
        val guiOpen: Boolean,
        val lookingAtBlock: Boolean,
        val chance: Int,
        val criticals: Boolean,
        val missedSwing: Int,
        val hasForwardTarget: Boolean,
        val forwardHurtResistantTime: Int,
        val waitFirstMs: Int,
        val retimeAt: Int,
        val pauseMs: Int
    )

    /** Advances the windows; returns whether CounterHit should fire this tick. */
    fun tick(state: State, input: TickInput): Boolean {
        state.canCritNow = input.canCrit

        if (!input.hasTarget) {
            state.trackedTargetId = -1
            state.prevHurtTime = 0
            return false
        }

        if (input.targetId != state.trackedTargetId) {
            state.trackedTargetId = input.targetId
            state.targetFirstSeenNs = input.nowNs
            state.lastConfirmedHitNs = -1L
            state.prevHurtTime = input.targetHurtTime
        }

        if (input.targetHurtTime > state.prevHurtTime) {
            state.lastConfirmedHitNs = input.nowNs
        }
        state.prevHurtTime = input.targetHurtTime

        return if (input.counterHit &&
            !input.leftMouseDown &&
            input.playerHurtTime > 0 &&
            input.nowNs - state.lastCounterNs >= input.counterCdMs * 1_000_000L
        ) {
            state.lastCounterNs = input.nowNs
            true
        } else {
            false
        }
    }

    /**
     * Returns true when the click should be delivered.
     *
     * [roll] is called at most twice, in gate order — `chance` first, then
     * `missedSwing` only on the branch that reaches it.
     */
    fun gate(state: State, input: GateInput, roll: () -> Int): Boolean {
        if (input.guiOpen || input.lookingAtBlock) return true
        if (input.chance < 100 && roll() >= input.chance) return true
        if (input.criticals && !state.canCritNow) return false

        if (!input.hasForwardTarget) {
            return !(input.missedSwing > 0 && roll() < input.missedSwing)
        }

        if (input.waitFirstMs > 0 &&
            state.targetFirstSeenNs >= 0 &&
            input.nowNs - state.targetFirstSeenNs < input.waitFirstMs * 1_000_000L
        ) {
            return false
        }

        if (input.forwardHurtResistantTime > input.retimeAt) return false

        if (input.pauseMs > 0 &&
            state.lastConfirmedHitNs >= 0 &&
            input.nowNs - state.lastConfirmedHitNs < input.pauseMs * 1_000_000L
        ) {
            return false
        }

        return true
    }
}
