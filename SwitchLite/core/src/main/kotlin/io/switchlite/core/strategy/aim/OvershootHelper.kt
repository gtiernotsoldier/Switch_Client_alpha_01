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
 * Movement is NOT a fixed degree cap: it closes a fraction of the remaining yaw/pitch gap each
 * tick (Nemui-style proportional glide), so the crosshair eases toward the aim point — tapering
 * off as it approaches, which reads as natural and stable.
 *
 * @param state the strategy state carrying overshoot phase + targets.
 * @param player current player snapshot.
 * @param targetPoint the computed ideal aim point (box-edge or center).
 * @param rotationDiff angular delta from current aim to target.
 * @param yawFactor fraction of the yaw gap closed per tick (0..1).
 * @param pitchFactor fraction of the pitch gap closed per tick (0..1).
 * @return the new rotation for this tick, or null (skip).
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
                val interpolated = moveTowards(player.rotation, targetPoint, yawFactor, pitchFactor)
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
                    moveTowards(player.rotation, osTarget, yawFactor, pitchFactor)
                } else {
                    interpolated
                }
            }
            AimStrategy.State.OvershootPhase.OVERSHOOT -> {
                val osTarget = state.overshootTarget ?: run {
                    state.resetOvershoot()
                    return null
                }
                val result = moveTowards(player.rotation, osTarget, yawFactor, pitchFactor)
                state.overshootTicksRemaining--
                if (state.overshootTicksRemaining <= 0) {
                    state.overshootPhase = AimStrategy.State.OvershootPhase.CORRECT
                }
                result
            }
            AimStrategy.State.OvershootPhase.CORRECT -> {
                val result = moveTowards(player.rotation, targetPoint, yawFactor * 1.2f, pitchFactor * 1.2f)
                state.resetOvershoot()
                result
            }
        }
    }

    /**
     * Nemui-style proportional glide: move a fraction of the remaining angular gap each tick,
     * so the crosshair eases toward the aim point (exponential decay), not a fixed degree cap.
     *
     * Nemui's SimpleAnimation: deltaValue = |to - from| * 0.35 / (10 / speed). This matches
     * how Nemui's AimAssist smooths — the bigger the gap the faster it moves, tapering off as
     * it approaches, which reads as natural and stable.
     *
     * @param yawFraction fraction of the yaw gap closed per tick (0..1).
     * @param pitchFraction fraction of the pitch gap closed per tick (0..1).
     */
    private fun moveTowards(current: Vec2, target: Vec2, yawFraction: Float, pitchFraction: Float): Vec2 {
        val diff = RotationCalculator.calculateDifference(current, target)
        val yawMove = diff.yaw * yawFraction
        val pitchMove = diff.pitch * pitchFraction
        return Vec2(current.yaw + yawMove, current.pitch + pitchMove)
    }
}

fun AimStrategy.State.resetOvershoot() {
    overshootPhase = AimStrategy.State.OvershootPhase.IDLE
    overshootTarget = null
    overshootTicksRemaining = 0
}
