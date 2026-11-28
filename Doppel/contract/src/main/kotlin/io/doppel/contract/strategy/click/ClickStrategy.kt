package io.doppel.contract.strategy.click

import io.doppel.contract.strategy.Strategy
import io.doppel.contract.strategy.StrategyContext

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
 * - 1.8: a rhythm of scheduled inter-click intervals — per-click CPS tempo,
 *   AR(1) interval jitter (bursts and lulls), rare re-acquisition pauses,
 *   and a reaction floor when the rhythm re-engages; LEGIT mode adjusts the
 *   tempo by target distance.
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
     * @property nextClickIn rhythm scheduler: ticks left before the next
     *   click is due. Meaningful only while [rhythmLive] is true.
     * @property jitter the persistent AR(1) component of the interval
     *   jitter, centred on 1.0.
     * @property rhythmLive false while the rhythm is idle (fresh state, or
     *   the gates broke): the next gate-passing tick re-arms with a fresh
     *   interval instead of resuming a half-counted countdown.
     */
    open class State : StrategyContext {
        var lastTargetId: Int = -1
        var pendingSecondClick: Boolean = false
        var nextClickIn: Int = 0
        var jitter: Float = 1f
        var rhythmLive: Boolean = false

        override fun reset() {
            lastTargetId = -1
            pendingSecondClick = false
            nextClickIn = 0
            jitter = 1f
            rhythmLive = false
        }
    }
}
