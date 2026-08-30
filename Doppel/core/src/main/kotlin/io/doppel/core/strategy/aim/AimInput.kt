package io.doppel.core.strategy.aim

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * Input bundle for [AimStrategy.execute].
 *
 * Wraps the per-tick snapshots extracted by the adapter's
 * [io.doppel.adapter.common.api.IStateExtractor].
 * Kept as a dedicated class (not just a Pair) for readability
 * and future extensibility (e.g. adding multiple targets).
 *
 * @property player current player snapshot.
 * @property target the selected target snapshot, or null if no target in range.
 */
data class AimInput(
    val player: PlayerState,
    val target: TargetState?,
    /** Raw mouse delta in pixels this frame (screen-space). Positive X = right. */
    val mouseDeltaX: Float = 0f,
    /** Raw mouse delta in pixels this frame (screen-space). Positive Y = up. */
    val mouseDeltaY: Float = 0f,
    /** Player's configured mouse sensitivity (from game settings). */
    val sensitivity: Float = 1.0f
)