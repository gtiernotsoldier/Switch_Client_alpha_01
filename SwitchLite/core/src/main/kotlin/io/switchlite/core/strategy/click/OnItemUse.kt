package io.switchlite.core.strategy.click

/**
 * Behaviour when the player is using an item (e.g. blocking with a shield,
 * eating food, drawing a bow) while AutoClicker wants to attack.
 *
 * @property WAIT   Skip this tick. Wait until the player stops using the item.
 * @property STOP   Release the item use (via EventBridge), then continue
 *   attacking this tick.
 * @property IGNORE Do not check. Attack regardless of item use state.
 */
enum class OnItemUse {
    /** Skip the tick while the player is using an item. */
    WAIT,

    /** Release the item use and continue attacking. */
    STOP,

    /** Ignore item use state entirely. */
    IGNORE
}
