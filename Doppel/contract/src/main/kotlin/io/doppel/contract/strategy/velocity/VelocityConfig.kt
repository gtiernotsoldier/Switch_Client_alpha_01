package io.doppel.contract.strategy.velocity

import io.doppel.contract.option.TriggerOptions

/**
 * Immutable configuration snapshot for [VelocityStrategy].
 *
 * Decoupled from adapter property delegates — the adapter reads
 * its delegated properties and constructs a new instance each
 * tick (or on config change).
 *
 * @property mode which reduction algorithm to apply.
 * @property horizontalMin minimum horizontal factor (e.g. 0.4 = 40% retained).
 * @property horizontalMax maximum horizontal factor.
 * @property verticalMin minimum vertical factor.
 * @property verticalMax maximum vertical factor.
 * @property reduceGivenReduced P(reduce | the previous hit was reduced). With [reduceGivenSkipped]
 *   this is the whole gate — one memoryless two-state chain, not a cadence.
 * @property reduceGivenSkipped P(reduce | the previous hit was skipped).
 * @property onlyOnHitFrame reduce only on an attack's knockback (S12); explosions (S27) pass.
 * @property triggerOptions unified condition engine settings.
 * @property clickBurstMin minimum clicks to burst in CLICK mode.
 * @property clickBurstMax maximum clicks to burst in CLICK mode.
 * @property hurtTimeToClick exact hurtTime value that triggers CLICK mode.
 * @property whenFacingEnemyOnly require the player to be looking at the target in CLICK mode.
 * @property maxAngleDifference maximum yaw difference (degrees) to count as "facing" in CLICK mode.
 * @property clickRange maximum distance (blocks) for CLICK mode activation.
 */
data class VelocityConfig(
    val mode: VelocityMode = VelocityMode.LEGIT,
    val horizontalMin: Float = 0.4f,
    val horizontalMax: Float = 0.6f,
    val verticalMin: Float = 0.4f,
    val verticalMax: Float = 0.6f,
    val reduceGivenReduced: Int = 25,
    /** P(reduce | the previous hit was skipped). */
    val reduceGivenSkipped: Int = 25,
    /** Reduce only on an attack's knockback (S12); explosions (S27) pass through untouched. */
    val onlyOnHitFrame: Boolean = false,
    val triggerOptions: TriggerOptions = TriggerOptions(),
    val clickBurstMin: Int = 2,
    val clickBurstMax: Int = 5,
    val hurtTimeToClick: Int = 8,
    val whenFacingEnemyOnly: Boolean = true,
    val maxAngleDifference: Float = 90f,
    val clickRange: Float = 3.0f,
    // ── SPRINT mode ──
    /** Ticks to keep pressing toward the knockback source after a hit. */
    val sprintHoldTicks: Int = 4,
    /** P(brace | braced on the previous hit). Equal to [sprintGateIdle] makes the gate memoryless. */
    val sprintGateBraced: Int = 25,
    /** P(brace | did not brace on the previous hit). */
    val sprintGateIdle: Int = 25,
    /** cos of the half-angle of the press cones (cos 67.5° ≈ 0.38). */
    val sprintSector: Float = 0.38f
)

/**
 * Velocity reduction mode.
 *
 * LEGIT  — scale the motion vector by a random factor in [min, max].
 * CLICK  — instead of modifying velocity, issue a click burst on the target.
 * SPRINT — never touch the packet at all; manipulate movement input + the
 *          server-side sprint mirror instead. The one mode whose result is
 *          computed by the server rather than overwritten on the way in
 *          (see the Velocity module KDoc).
 */
enum class VelocityMode {
    LEGIT,
    CLICK,
    SPRINT
}
