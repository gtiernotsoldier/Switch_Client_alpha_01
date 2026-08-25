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
 * @property mode LEGIT (box-edge tracking) or NORMAL (crosshair-point lock).
 * @property rangeMin minimum 3D distance (blocks).
 * @property rangeMax maximum 3D distance (blocks).
 * @property fov angular FOV cone measured around the view line (0-360°); radius = fov / 2.
 * @property aimSpeed raw speed slider (1-20). Mapped to Nemui-style proportional smoothing.
 * @property smoothness smoothing multiplier (0.0-1.0).
 * @property noiseIntensity magnitude of per-frame random-walk noise (degrees).
 * @property lockOnCrosshair when true, only assist once the crosshair is already aligned to the
 *        target (within a small angle). Off = assist anywhere inside the FOV cone.
 * @property triggerOptions unified condition engine settings.
 */
data class AimConfig(
    val mode: AimMode = AimMode.LEGIT,
    val rangeMin: Float = 3.0f,
    val rangeMax: Float = 6.0f,
    val fov: Float = 360.0f,
    val aimSpeed: Int = 8,
    val smoothness: Float = 0.85f,
    val noiseIntensity: Float = 0.05f,
    val lockOnCrosshair: Boolean = false,
    val triggerOptions: TriggerOptions = TriggerOptions()
)