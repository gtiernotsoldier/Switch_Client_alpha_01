package io.switchlite.core.strategy.click

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * Input bundle for [ClickStrategy.execute].
 *
 * @property player current player snapshot.
 * @property target the selected target, or null if none.
 */
data class ClickInput(
    val player: PlayerState,
    val target: TargetState?
)
