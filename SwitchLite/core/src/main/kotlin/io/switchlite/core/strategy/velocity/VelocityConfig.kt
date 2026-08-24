package io.switchlite.core.strategy.velocity

import io.switchlite.core.option.TriggerOptions

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
 * @property probability chance (0-100) that the strategy activates per packet.
 * @property delayMs extra delay in milliseconds before releasing the packet.
 * @property delayTicks extra delay in server ticks before releasing the packet.
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
    val probability: Int = 100,
    val delayMs: Int = 0,
    val delayTicks: Int = 0,
    val triggerOptions: TriggerOptions = TriggerOptions(),
    val clickBurstMin: Int = 2,
    val clickBurstMax: Int = 5,
    val hurtTimeToClick: Int = 8,
    val whenFacingEnemyOnly: Boolean = true,
    val maxAngleDifference: Float = 90f,
    val clickRange: Float = 3.0f
)

/**
 * Velocity reduction mode.
 *
 * LEGIT  — scale the motion vector by a random factor in [min, max].
 * DELAY  — hold the packet for N ticks then release with LEGIT scaling.
 * CLICK  — instead of modifying velocity, issue a click burst on the target.
 */
enum class VelocityMode {
    LEGIT,
    DELAY,
    CLICK
}
