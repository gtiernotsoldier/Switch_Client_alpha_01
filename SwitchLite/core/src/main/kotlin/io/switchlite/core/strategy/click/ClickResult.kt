package io.switchlite.core.strategy.click

/**
 * Typed result from a [ClickStrategy] execution.
 *
 * The adapter maps each variant to the appropriate action:
 *
 * - [Click]  → `EventBridge.triggerAttack()`.
 * - [Skip]   → do nothing this tick.
 */
sealed class ClickResult {

    /**
     * Issue a single left-click attack.
     */
    object Click : ClickResult()

    /**
     * Do nothing this tick (condition fail, CPS probability missed, etc.).
     */
    object Skip : ClickResult()
}
