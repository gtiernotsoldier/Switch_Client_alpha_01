package io.switchlite.core.strategy.aim

import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext
import io.switchlite.core.util.Vec2

/**
 * Strategy for computing aim-assist rotations.
 *
 * Consumes combat-relevant snapshots ([AimInput]) and produces
 * an [AimResult] that tells the adapter what rotation to set
 * (if any).
 *
 * The strategy encapsulates all humanization logic — smoothing,
 * noise injection, overshoot simulation, and reaction delay —
 * keeping the adapter module a thin mapping layer.
 */
interface AimStrategy : Strategy<AimConfig, AimStrategy.State, AimResult> {

    /**
     * Mutable per-session state for aim processing.
     *
     * Carries the overshoot state machine and reaction delay counter.
     * All fields are reset when the module is re-enabled.
     */
    class State : StrategyContext {
        /**
         * Three-phase overshoot simulation state machine.
         *
         * IDLE      — normal tracking; may transition to OVERSHOOT.
         * OVERSHOOT — mouse is moving past the target (1-2 ticks).
         * CORRECT   — snapping back toward the real target point.
         */
        enum class OvershootPhase { IDLE, OVERSHOOT, CORRECT }

        var overshootPhase: OvershootPhase = OvershootPhase.IDLE
        var overshootTarget: Vec2? = null
        var overshootTicksRemaining: Int = 0
        var lastTargetId: Int = -1
        var reactionDelayTicks: Int = 0

        override fun reset() {
            overshootPhase = OvershootPhase.IDLE
            overshootTarget = null
            overshootTicksRemaining = 0
            lastTargetId = -1
            reactionDelayTicks = 0
        }
    }
}
