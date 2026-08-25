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

        override fun reset() {
            lastTargetId = -1
        }
    }
}
