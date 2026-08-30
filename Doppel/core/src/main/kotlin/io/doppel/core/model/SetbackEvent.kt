package io.doppel.core.model

import io.doppel.core.util.Vec3

/**
 * A single movement vector output by a movement module at a specific tick.
 * Used by SetbackTracker to attribute blame when a setback occurs.
 *
 * Pure data, zero platform dependencies.
 *
 * @property moduleId Identifier for the module that produced this movement.
 *   String-based to avoid coupling to concrete module classes.
 *   Examples: "Sprint", "Strafe", "Speed", "Flight".
 * @property tick The game tick at which this movement was output.
 * @property movement The movement vector (delta position) output by the module.
 */
data class ModuleMovementSample(
    val moduleId: String,
    val tick: Long,
    val movement: Vec3
)

/**
 * Snapshot of a setback (anti-cheat pull-back) event.
 *
 * Captures the state at the moment of setback so that [SetbackTracker]
 * can determine blame attribution without any live game access.
 *
 * Pure data, zero platform dependencies.
 *
 * @property tick The game tick at which the setback occurred.
 * @property positionBefore Player position immediately before the setback.
 * @property positionAfter Player position immediately after the setback (server-corrected).
 * @property recentMovementSamples Movement samples from movement modules within
 *   the lookback window (last N ticks before this setback). The adapter layer
 *   is responsible for collecting and attaching these samples.
 */
data class SetbackEvent(
    val tick: Long,
    val positionBefore: Vec3,
    val positionAfter: Vec3,
    val recentMovementSamples: List<ModuleMovementSample>
) {
    /**
     * The displacement caused by the setback itself.
     * Useful for logging / severity analysis.
     */
    val displacement: Vec3 get() = positionBefore - positionAfter

    /**
     * Magnitude of the setback displacement (how far the player was pulled back).
     */
    val displacementMagnitude: Double get() = displacement.length()
}

/**
 * Verdict produced by [io.doppel.core.safety.SetbackTracker] after processing
 * a setback event. Tells the caller what action to take (if any).
 *
 * The tracker itself does NOT execute the action — it only produces the decision.
 * The adapter layer or module manager is responsible for acting on it.
 */
sealed class SetbackVerdict {

    /**
     * External cause (network lag, knockback, server desync, etc.).
     * No movement module is to blame. Record only, no action needed.
     */
    data class ExternalCause(
        val tick: Long,
        val totalSetbacks: Int
    ) : SetbackVerdict()

    /**
     * A movement module caused the setback. Below circuit-break threshold.
     * Caller should pause the blamed module as a precaution.
     */
    data class BlameModule(
        val moduleId: String,
        val blameCount: Int,
        val threshold: Int
    ) : SetbackVerdict()

    /**
     * Circuit-break threshold reached. The blamed module must be hard-disabled.
     * Continued operation risks a ban on the 3rd setback.
     */
    data class CircuitBreak(
        val moduleId: String,
        val blameCount: Int
    ) : SetbackVerdict()
}
