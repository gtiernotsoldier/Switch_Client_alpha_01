package io.doppel.contract.strategy.click

import io.doppel.contract.option.ClickMode
import io.doppel.contract.option.TriggerOptions

/**
 * Immutable configuration snapshot for [ClickStrategy].
 *
 * @property minCps minimum clicks per second.
 * @property maxCps maximum clicks per second.
 * @property clickMode SINGLE (one click) or DOUBLE (two clicks per interval).
 * @property mode NORMAL (fixed CPS range) or LEGIT (distance-adjusted CPS).
 * @property disableOnBlock skip clicks while the player is mining a block.
 * @property triggerOptions unified condition engine settings.
 */
data class ClickConfig(
    val minCps: Int = 8,
    val maxCps: Int = 10,
    val clickMode: ClickMode = ClickMode.SINGLE,
    val mode: ClickOperatingMode = ClickOperatingMode.NORMAL,
    val disableOnBlock: Boolean = true,
    val triggerOptions: TriggerOptions = TriggerOptions(),
    // ── 1.8 rhythm shaping (tunable) ────────────────────────────────────
    // Short names on purpose; defaults match the old consts bit-for-bit.
    /** AR(1) persistence of the interval jitter. 0 = no bursts, 1 = frozen. */
    val jitter: Float = 0.65f,
    /** Jitter target drawn in [jitterMin, jitterMin + jitterVar). */
    val jitterMin: Float = 0.8f,
    val jitterVar: Float = 0.4f,
    /** Chance of a rare long (pause / re-acquisition) interval. */
    val pause: Float = 0.05f,
    val pauseLow: Float = 2.0f,
    val pauseVar: Float = 2.0f,
    /** Reaction floor in ticks after the gates break. 0 removes the floor. */
    val react: Int = 4
)

/**
 * AutoClicker operating mode.
 * NORMAL: fixed random CPS within [minCps, maxCps].
 * LEGIT: dynamic CPS adjusted by target distance.
 */
enum class ClickOperatingMode {
    NORMAL,
    LEGIT
}
