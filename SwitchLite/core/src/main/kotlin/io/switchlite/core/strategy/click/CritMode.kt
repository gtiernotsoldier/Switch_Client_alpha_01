package io.switchlite.core.strategy.click

/**
 * Critical hit behaviour for 1.9+ cooldown-based clicking.
 *
 * In 1.9+ Minecraft, critical hits require the player to be
 * **falling** (negative motionY, airborne) and **not sprinting**.
 * This enum controls how the strategy handles crits.
 *
 * @property OFF   Ignore crits entirely. Click as soon as cooldown
 *   reaches threshold.
 * @property ON    Wait for fall + cooldown before every hit.
 *   Every hit is a crit. Uses the full crit state machine.
 * @property SMART Try to crit when conditions are already met;
 *   if not, hit normally without waiting. Never delays a hit
 *   to create crit conditions.
 */
enum class CritMode {
    /** No crit logic. Click the instant cooldown is ready. */
    OFF,

    /** Full crit state machine: stop sprint → wait fall → wait cooldown → attack → restore. */
    ON,

    /** Opportunistic: crit if already falling + not sprinting, otherwise hit normally. */
    SMART
}
