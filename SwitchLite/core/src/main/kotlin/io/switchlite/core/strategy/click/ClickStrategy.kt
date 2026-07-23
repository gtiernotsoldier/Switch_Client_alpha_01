package io.switchlite.core.strategy.click

import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext

/**
 * Strategy for auto-clicking behaviour.
 *
 * Consumes [ClickInput] and produces a [ClickResult] telling
 * the adapter whether to issue an attack this tick.
 *
 * [C] the configuration type (e.g. [ClickConfig] for 1.8,
 *   [CooldownClickConfig] for 1.9+).
 * [S] the mutable state type, must extend [State].
 *
 * Humanization is achieved through:
 * - 1.8: Probabilistic CPS + distance-adjusted CPS (LEGIT mode).
 * - 1.9+: Cooldown-wait + crit state machine + LEGIT delay.
 * - Both: block-hit prevention, unified condition checks.
 */
interface ClickStrategy<C, S : ClickStrategy.State> : Strategy<C, S, ClickResult> {

    /**
     * Mutable per-session state for click processing.
     *
     * @property lastTargetId entity ID of the target on the previous tick.
     * @property pendingSecondClick true when a DOUBLE-mode first click
     *   was issued last tick and the second click should fire this tick.
     */
    open class State : StrategyContext {
        var lastTargetId: Int = -1
        var pendingSecondClick: Boolean = false

        override fun reset() {
            lastTargetId = -1
            pendingSecondClick = false
        }
    }
}
