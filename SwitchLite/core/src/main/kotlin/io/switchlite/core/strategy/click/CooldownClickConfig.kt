package io.switchlite.core.strategy.click

import io.switchlite.core.option.TriggerOptions

/**
 * Immutable configuration snapshot for [CooldownClickStrategy] (1.9+ combat).
 *
 * In 1.9+ Minecraft, attack speed is governed by a cooldown bar (0.0–1.0).
 * The player can only deal full damage when the cooldown is at or above
 * [cooldownThreshold]. This strategy waits for the cooldown to reach the
 * threshold before issuing a click.
 *
 * @property cooldownThreshold fraction of the cooldown bar that must be
 *   reached before attacking. 1.0 = wait for full charge (default),
 *   0.5 = attack at half cooldown (faster but reduced damage).
 * @property critEnabled when true, the strategy will attempt to land
 *   every hit as a critical hit. In 1.9+, crits require:
 *   1. Player is falling (negative motionY, airborne)
 *   2. Player is NOT sprinting
 *   When enabled, the strategy emits [ClickResult.StopSprint] before
 *   the attack and [ClickResult.RestoreSprint] after.
 * @property critStopSprint when true (and [critEnabled] is true),
 *   automatically stop sprinting before a crit hit and restore after.
 *   When false, the player must manually stop sprinting for crits.
 * @property cooldownMode NORMAL: attack as soon as cooldown reaches threshold.
 *   LEGIT: add a random 1-3 tick delay after cooldown reaches threshold,
 *   simulating human reaction time.
 * @property disableOnBlock skip clicks while the player is mining a block.
 * @property triggerOptions unified condition engine settings.
 */
data class CooldownClickConfig(
    val cooldownThreshold: Float = 1.0f,
    val critEnabled: Boolean = false,
    val critStopSprint: Boolean = true,
    val cooldownMode: CooldownClickMode = CooldownClickMode.NORMAL,
    val disableOnBlock: Boolean = true,
    val triggerOptions: TriggerOptions = TriggerOptions()
) {
    init {
        require(cooldownThreshold in 0.5f..1.0f) {
            "cooldownThreshold must be in 0.5..1.0, got $cooldownThreshold"
        }
    }
}

/**
 * Operating mode for 1.9+ cooldown-based clicking.
 *
 * NORMAL: attack the instant the cooldown bar reaches [CooldownClickConfig.cooldownThreshold].
 * LEGIT:  wait for cooldown + an additional random 1-3 tick delay to simulate human reaction.
 */
enum class CooldownClickMode {
    NORMAL,
    LEGIT
}