package io.switchlite.core.strategy.click

/**
 * Typed result from a [ClickStrategy] execution.
 *
 * The adapter maps each variant to the appropriate action:
 *
 * - [Click]          → `EventBridge.triggerAttack()`.
 * - [StopSprint]     → `EventBridge.setSprinting(false)`.
 * - [RestoreSprint]  → `EventBridge.setSprinting(true)`.
 * - [Skip]           → do nothing this tick.
 */
sealed class ClickResult {

    /**
     * Issue a single left-click attack.
     */
    object Click : ClickResult()

    /**
     * Stop sprinting. Used by 1.9+ crit logic to disable sprint
     * before a critical hit (sprinting negates crits in 1.9+).
     *
     * The adapter should set the player's sprinting state to false.
     */
    object StopSprint : ClickResult()

    /**
     * Restore sprinting after a critical hit.
     * The adapter should re-enable sprinting if the player was
     * sprinting before [StopSprint] was emitted.
     *
     * @property wasSprinting true if the player was sprinting before
     *   the crit sequence started. The adapter uses this to decide
     *   whether to actually restore.
     */
    data class RestoreSprint(val wasSprinting: Boolean) : ClickResult()

    /**
     * Do nothing this tick (condition fail, CPS probability missed, cooldown not ready, etc.).
     */
    object Skip : ClickResult()
}