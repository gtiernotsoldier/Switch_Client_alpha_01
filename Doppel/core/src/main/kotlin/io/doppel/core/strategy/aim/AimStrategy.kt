package io.doppel.core.strategy.aim

import io.doppel.core.strategy.Strategy
import io.doppel.core.strategy.StrategyContext
import io.doppel.core.util.Vec2

/**
 * Strategy for computing aim-assist rotations.
 *
 * Consumes combat-relevant snapshots ([AimInput]) and produces
 * an [AimResult] that tells the adapter what rotation to set
 * (if any).
 *
 * The strategy encapsulates the small amount of aim math — target point
 * selection, FOV cone, and Nemui-style proportional smoothing — keeping
 * the adapter module a thin mapping layer.
 */
interface AimStrategy : Strategy<AimConfig, AimStrategy.State, AimResult> {

    /**
     * Mutable per-session state for aim processing.
     *
     * Only tracks the last target id so SelfAdaptive can reset its alignment
     * tracking when the target changes. All fields are reset when re-enabled.
     */
    open class State : StrategyContext {
        var lastTargetId: Int = -1

        /** Slowly-drifting natural aim offset (degrees) — target angles shift within ±offset so
         *  the crosshair never locks dead-on. Re-randomized toward a new goal periodically. */
        var driftYaw: Float = 0f
        var driftPitch: Float = 0f
        var driftGoalYaw: Float = 0f
        var driftGoalPitch: Float = 0f
        var driftTicksLeft: Int = 0

        override fun reset() {
            lastTargetId = -1
            driftYaw = 0f
            driftPitch = 0f
            driftGoalYaw = 0f
            driftGoalPitch = 0f
            driftTicksLeft = 0
        }
    }
}
