package io.switchlite.core.strategy.aim

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * Input bundle for [AimStrategy.execute].
 *
 * Wraps the per-tick snapshots extracted by the adapter's
 * [io.switchlite.adapter.common.api.IStateExtractor].
 * Kept as a dedicated class (not just a Pair) for readability
 * and future extensibility (e.g. adding multiple targets).
 *
 * @property player current player snapshot.
 * @property target the selected target snapshot, or null if no target in range.
 */
data class AimInput(
    val player: PlayerState,
    val target: TargetState?
)