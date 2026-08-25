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
 * @property mode LEGIT / NORMAL / SELF_ADAPTIVE / LINEAR.
 * @property rangeMin minimum 3D distance (blocks).
 * @property rangeMax maximum 3D distance (blocks).
 * @property fov max search FOV cone (0-360°; 360 = full).
 * @property minFov minimum FOV (degrees): while the crosshair is within this angle of the target
 *        center, aim freely (inside the hitbox) — the assist only pulls when the crosshair drifts
 *        outside. Hides the assist while recording / feels natural.
 * @property aimSpeedY yaw pull fraction per frame (0..1).
 * @property aimSpeedP pitch pull fraction per frame (0..1, independent axis speed).
 * @property smoothness smoothing multiplier (0.0-1.0).
 * @property noiseIntensity magnitude of per-frame random-walk noise (degrees).
 * @property offset natural aim offset (degrees): the aim point drifts slowly within ±offset so the
 *        crosshair never locks dead-on the target — reads as a human hand, not a machine.
 * @property multipointX horizontal multipoint (0 = hitbox center, 1 = closest corner), Slinky-style.
 * @property multipointY vertical multipoint (0 = center, 1 = closest edge).
 * @property linear when true, use near-linear speed (Slinky Linear mode — stable low-speed
 *        tracking); false = Regular exponential speed.
 * @property lockOnCrosshair when true, only assist once the crosshair is already aligned to the
 *        target (within a small angle). Off = assist anywhere inside the FOV cone.
 * @property triggerOptions unified condition engine settings.
 */
data class AimConfig(
    val mode: AimMode = AimMode.LEGIT,
    val rangeMin: Float = 3.0f,
    val rangeMax: Float = 6.0f,
    val fov: Float = 360.0f,
    val minFov: Float = 0.0f,
    val aimSpeedY: Float = 0.25f,
    val aimSpeedP: Float = 0.1f,
    val smoothness: Float = 0.85f,
    val noiseIntensity: Float = 0.05f,
    val offset: Float = 0.5f,
    val multipointX: Float = 0.5f,
    val multipointY: Float = 0.5f,
    val linear: Boolean = false,
    val lockOnCrosshair: Boolean = false,
    val triggerOptions: TriggerOptions = TriggerOptions()
)