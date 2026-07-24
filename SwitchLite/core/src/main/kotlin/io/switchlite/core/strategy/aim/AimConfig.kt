package io.switchlite.core.strategy.aim

import io.switchlite.core.option.AimMode
import io.switchlite.core.option.TriggerOptions

/**
 * Immutable configuration snapshot for [AimStrategy].
 *
 * This is the pure-data representation of all aim-assist settings.
 * The adapter constructs a new instance from its delegated properties
 * each tick (or on config change).
 *
 * @property mode LEGIT (box-edge tracking) or NORMAL (center/random point).
 * @property rangeMin minimum horizontal distance (X/Z) in blocks.
 * @property rangeMax maximum horizontal distance (X/Z) in blocks.
 * @property horizontalFov maximum yaw deviation allowed (degrees).
 * @property verticalFov maximum pitch deviation allowed (degrees).
 * @property aimSpeed raw speed slider (1-20). Converted to a factor internally.
 * @property smoothness interpolation factor modifier (0.0-1.0).
 * @property noiseIntensity magnitude of per-frame random-walk noise (degrees).
 * @property lockOnCrosshair when true (NORMAL mode), aim at the hitbox center.
 * @property triggerOptions unified condition engine settings.
 */
data class AimConfig(
    val mode: AimMode = AimMode.LEGIT,
    val rangeMin: Float = 3.0f,
    val rangeMax: Float = 6.0f,
    val horizontalFov: Float = 90.0f,
    val verticalFov: Float = 60.0f,
    val aimSpeed: Int = 8,
    val smoothness: Float = 0.85f,
    val noiseIntensity: Float = 0.05f,
    val lockOnCrosshair: Boolean = false,
    val triggerOptions: TriggerOptions = TriggerOptions()
)