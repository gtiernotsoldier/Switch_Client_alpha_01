package io.doppel.core.strategy.tap

import io.doppel.core.model.TargetState
import io.doppel.core.util.Vec3
import kotlin.math.atan2
import kotlin.random.Random

/** Strafe side for ADTap (keyboard A/D only — never the mouse). */
enum class Side {
    LEFT, RIGHT;

    /** The opposite side. */
    fun flipped(): Side = if (this == LEFT) RIGHT else LEFT
}

/**
 * Pure decision layer for ADTap (A/D strafe-pattern automation).
 *
 * ADTap is NOT a sprint reset — in 1.8 game mechanics, tapping A/D while holding W
 * never touches the sprint state (unlike releasing W = w-tap, or pressing S = s-tap).
 * What it produces is a zig-zag ("7-tap") movement pattern that is hard to track and
 * keeps the player aligned with a combo. This object models side selection only;
 * durations/gaps are plain uniform draws in the module (same style as WTap/STap).
 *
 * Pure Core layer: zero platform dependencies, no key simulation, deterministic
 * given a seeded [Random].
 */
object StrafePattern {

    /** Wrap an angle into [-180, 180). */
    fun wrap180(angle: Float): Float {
        var a = angle
        while (a >= 180f) a -= 360f
        while (a < -180f) a += 360f
        return a
    }

    /**
     * Chase correction: which strafe key leads toward the target's predicted position.
     * Linear motion prediction over [leadTicks] (20 ticks = 1 second).
     *
     * Minecraft yaw convention: 0 faces +Z, 90 faces -X, so the yaw that would face
     * the aim point is `atan2(-dx, dz)` in degrees; a negative wrapped delta
     * (bearing - yaw) means the point is on the player's LEFT (verified: player at
     * origin yaw 0 facing +Z, target at +X/east = left hand side → bearing < 0).
     */
    fun chaseSide(yaw: Float, playerPos: Vec3, target: TargetState, leadTicks: Int): Side {
        val k = leadTicks / 20.0
        val ax = target.position.x + target.motionX * k
        val az = target.position.z + target.motionZ * k
        val dx = ax - playerPos.x
        val dz = az - playerPos.z
        val bearing = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val delta = wrap180(bearing - yaw)
        return if (delta < 0f) Side.LEFT else Side.RIGHT
    }

    /**
     * 7-tap side selection: alternating in principle, with a small configurable
     * probability of repeating the same side instead of flipping.
     */
    fun sevenTapSide(lastSide: Side, repeatChancePercent: Int, rng: Random): Side {
        return if (repeatChancePercent > 0 && rng.nextInt(100) < repeatChancePercent) lastSide
        else lastSide.flipped()
    }

    /** Uniform side draw for the Random pattern. */
    fun randomSide(rng: Random): Side = if (rng.nextInt(2) == 0) Side.LEFT else Side.RIGHT
}
