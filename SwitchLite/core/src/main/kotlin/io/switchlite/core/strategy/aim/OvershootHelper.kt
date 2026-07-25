package io.switchlite.core.strategy.aim

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.algorithm.RotationCalculator
import io.switchlite.core.model.PlayerState
import io.switchlite.core.util.Vec2
import kotlin.math.abs

/**
 * Shared overshoot state machine for aim strategies.
 *
 * Simulates human over-aiming: when making a significant direction change,
 * the mouse may overshoot past the target (15-20% chance), then correct back.
 *
 * Three-phase FSM:
 *   IDLE      → (20% chance, angularSize > 5°) → OVERSHOOT (1-2 ticks)
 *   OVERSHOOT → countdown → CORRECT (1 tick, accelerated correction)
 *   CORRECT   → IDLE
 *
 * Used by both [LegitAimStrategy] and [SelfAdaptiveAimStrategy].
 *
 * @param state the strategy state carrying overshoot phase + targets.
 * @param player current player snapshot.
 * @param targetPoint the computed ideal aim point (box-edge or center).
 * @param rotationDiff angular delta from current aim to target.
 * @param yawFactor interpolation factor for yaw.
 * @param pitchFactor interpolation factor for pitch.
 * @return the interpolated rotation for this tick, or null (skip).
 */
object OvershootHelper {

    fun execute(
        state: AimStrategy.State,
        player: PlayerState,
        targetPoint: Vec2,
        rotationDiff: Vec2,
        yawFactor: Float,
        pitchFactor: Float
    ): Vec2? {
        return when (state.overshootPhase) {
            AimStrategy.State.OvershootPhase.IDLE -> {
                val interpolated = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                val angularSize = abs(rotationDiff.yaw) + abs(rotationDiff.pitch)
                if (angularSize > 5f && NoiseProvider.nextUniform(0f, 1f) < 0.20f) {
                    val delta = RotationCalculator.calculateDifference(player.rotation, targetPoint)
                    val overshootPercent = 0.05f + NoiseProvider.nextUniform(0f, 1f) * 0.10f // 5-15%
                    state.overshootTarget = Vec2(
                        targetPoint.yaw + delta.yaw * overshootPercent,
                        targetPoint.pitch + delta.pitch * overshootPercent
                    )
                    state.overshootTicksRemaining =
                        if (NoiseProvider.nextUniform(0f, 1f) < 0.5f) 1 else 2
                    state.overshootPhase = AimStrategy.State.OvershootPhase.OVERSHOOT
                    val osTarget = state.overshootTarget ?: return null
                    RotationCalculator.interpolate(
                        current = player.rotation,
                        target = osTarget,
                        yawFactor = yawFactor,
                        pitchFactor = pitchFactor
                    )
                } else {
                    interpolated
                }
            }
            AimStrategy.State.OvershootPhase.OVERSHOOT -> {
                val osTarget = state.overshootTarget ?: run {
                    state.resetOvershoot()
                    return null
                }
                val result = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = osTarget,
                    yawFactor = yawFactor,
                    pitchFactor = pitchFactor
                )
                state.overshootTicksRemaining--
                if (state.overshootTicksRemaining <= 0) {
                    state.overshootPhase = AimStrategy.State.OvershootPhase.CORRECT
                }
                result
            }
            AimStrategy.State.OvershootPhase.CORRECT -> {
                val result = RotationCalculator.interpolate(
                    current = player.rotation,
                    target = targetPoint,
                    yawFactor = yawFactor * 1.2f,  // accelerated correction
                    pitchFactor = pitchFactor * 1.2f
                )
                state.resetOvershoot()
                result
            }
        }
    }

    fun AimStrategy.State.resetOvershoot() {
        overshootPhase = AimStrategy.State.OvershootPhase.IDLE
        overshootTarget = null
        overshootTicksRemaining = 0
    }
}
