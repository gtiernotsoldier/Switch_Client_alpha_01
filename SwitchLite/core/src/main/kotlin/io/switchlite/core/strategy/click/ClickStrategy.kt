package io.switchlite.core.strategy.click

import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext

/**
 * Strategy for auto-clicking behaviour.
 *
 * Consumes [ClickInput] and produces a [ClickResult] telling
 * the adapter whether to issue an attack this tick.
 *
 * Humanization is achieved through:
 * - Probabilistic CPS (effective CPS is converted to per-tick chance).
 * - Distance-aware CPS adjustment (LEGIT mode).
 * - Double-click support (first click immediately, second deferred).
 */
interface ClickStrategy : Strategy<ClickConfig, ClickStrategy.State, ClickResult> {

    /**
     * Mutable per-session state for click processing.
     *
     * @property lastTargetId entity ID of the target on the previous tick.
     * @property pendingSecondClick true when a DOUBLE-mode first click
     *   was issued last tick and the second click should fire this tick.
     */
    class State : StrategyContext {
        var lastTargetId: Int = -1
        var pendingSecondClick: Boolean = false

        override fun reset() {
            lastTargetId = -1
            pendingSecondClick = false
        }
    }
}
