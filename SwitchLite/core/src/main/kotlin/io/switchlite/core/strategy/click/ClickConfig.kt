package io.switchlite.core.strategy.click

import io.switchlite.core.option.ClickMode
import io.switchlite.core.option.TriggerOptions

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
    val triggerOptions: TriggerOptions = TriggerOptions()
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
