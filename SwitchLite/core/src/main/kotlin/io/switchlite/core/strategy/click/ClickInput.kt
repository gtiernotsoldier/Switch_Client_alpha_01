package io.switchlite.core.strategy.click

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * Input bundle for [ClickStrategy.execute].
 *
 * Fields beyond [player] and [target] are optional and default to
 * values that make 1.8-style strategies ignore them.
 * 1.9+ adapters populate all fields; 1.8 adapters can use the
 * two-arg constructor.
 *
 * @property player current player snapshot.
 * @property target the selected target, or null if none.
 * @property attackCooldown current attack cooldown as a fraction in 0.0..1.0.
 *   1.0 = fully charged, 0.0 = just attacked. 1.8 adapters pass 1.0 (always ready).
 * @property isFalling true when the player has negative vertical motion and is airborne.
 *   Used by 1.9+ crit detection. 1.8 adapters pass false.
 */
data class ClickInput(
    val player: PlayerState,
    val target: TargetState?,
    val attackCooldown: Float = 1.0f,
    val isFalling: Boolean = false
)