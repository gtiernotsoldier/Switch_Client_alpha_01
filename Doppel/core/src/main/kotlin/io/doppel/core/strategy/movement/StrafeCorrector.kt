package io.doppel.core.strategy.movement

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max
import io.doppel.contract.math.PortableMath

/**
 * StrafeCorrector — decides which **one** of the player's own movement keys to damp.
 *
 * It never adds input. The player decides where to go; this removes the keystroke that makes the
 * movement easier to track, and only when the current combination is clearly worse than a
 * neighbour that differs by exactly one released key.
 *
 * ## Geometry
 *
 * An opponent's aim is a direction, and a hit needs his aim error to stay inside the player's
 * angular size, `atan(hitboxRadius / distance)`. What makes tracking hard is the rate at which the
 * line of sight rotates: `v_tangential / distance`.
 *
 * Walking straight at him gives zero — he never has to move the mouse. Moving along the tangent
 * gives the most the player's own footspeed can produce.
 *
 * ## What this cannot do (measured, not assumed)
 *
 * `behavioral-tests/strafe_geometry_lab.py` runs the 1.8 movement integral against a tracking
 * opponent. At 2.5 blocks a sprinting circle asks for about **6.6 deg/tick**, while a human hand
 * can turn roughly **28 deg/tick**. Angular rate alone therefore never outruns a perfect tracker:
 * in that experiment a constant circle was inside the hit window 100% of the time, and only
 * alternating the direction (which forces the opponent to stop and reverse) brought it down.
 *
 * So this corrector optimises the part it can control — the tangential share of the input, at a
 * distance where the angular size stays small — and does not pretend to make the player un-hittable.
 */
object StrafeCorrector {

    data class Input(
        val forward: Boolean = false,
        val back: Boolean = false,
        val left: Boolean = false,
        val right: Boolean = false
    ) {
        val any: Boolean get() = forward || back || left || right
    }

    data class State(
        /** Distance to the target, blocks. */
        val distance: Double,
        /** World-space bearing from the player to the target, radians. */
        val bearingRad: Double,
        /** Player yaw in degrees (Minecraft: forward = (-sin, cos)). */
        val yawDeg: Double,
        /** Attack reach, blocks. */
        val reach: Double = 3.0
    )

    data class Correction(
        val suppressForward: Boolean = false,
        val suppressBack: Boolean = false,
        val suppressLeft: Boolean = false,
        val suppressRight: Boolean = false,
        val before: Double = 0.0,
        val after: Double = 0.0
    ) {
        val changed: Boolean
            get() = suppressForward || suppressBack || suppressLeft || suppressRight
    }

    /**
     * How much better a neighbour must score before a key is damped.
     *
     * Below this the player's own intent wins. He may be chasing, retreating or holding an angle,
     * none of which this geometry understands — a corrector that always acts is a controller, and
     * that is a different module.
     */
    const val MIN_GAIN = 0.15

    fun correct(input: Input, s: State): Correction {
        if (!input.any) return Correction()

        val before = score(input, s)

        // Only "release one key" neighbours. Adding input is an assist, not a correction, and
        // mixing the two makes the module impossible to reason about.
        val neighbours = buildList {
            if (input.forward) add(input.copy(forward = false) to 0)
            if (input.back) add(input.copy(back = false) to 1)
            if (input.left) add(input.copy(left = false) to 2)
            if (input.right) add(input.copy(right = false) to 3)
        }
        if (neighbours.isEmpty()) return Correction(before = before, after = before)

        val best = neighbours.maxByOrNull { score(it.first, s) } ?: return Correction()
        val after = score(best.first, s)
        if (after < before + MIN_GAIN) return Correction(before = before, after = before)

        return Correction(
            suppressForward = best.second == 0,
            suppressBack = best.second == 1,
            suppressLeft = best.second == 2,
            suppressRight = best.second == 3,
            before = before,
            after = after
        )
    }

    /**
     * Score an input combination: tangential share per block of distance, minus the penalty for
     * closing in while already inside reach.
     *
     * Only the ordering between neighbours matters, so the terms are deliberately simple.
     */
    internal fun score(input: Input, s: State): Double {
        if (!input.any) return 0.0

        val yaw = PortableMath.toRadians(s.yawDeg)
        val fx = -PortableMath.sin(yaw)
        val fz = PortableMath.cos(yaw)          // Minecraft forward
        val rx = PortableMath.cos(yaw)
        val rz = PortableMath.sin(yaw)          // right of forward

        var vx = 0.0
        var vz = 0.0
        if (input.forward) { vx += fx; vz += fz }
        if (input.back) { vx -= fx; vz -= fz }
        if (input.right) { vx += rx; vz += rz }
        if (input.left) { vx -= rx; vz -= rz }

        // sqrt, not hypot: Java's Math.hypot is fdlibm's scaling algorithm and
        // glibc's is correctly rounded, so the two disagree by an ulp on inputs
        // like these. Components here are bounded by 2, so the overflow guard
        // hypot exists for cannot trigger — and bit-exact parity with the Rust
        // port is worth more than a guard that never runs.
        val len = sqrt(vx * vx + vz * vz)
        // W+S or A+D cancel: no movement direction, no claim to make about it.
        if (len < 1e-6) return 0.0
        vx /= len
        vz /= len

        val bx = PortableMath.cos(s.bearingRad)
        val bz = PortableMath.sin(s.bearingRad)

        // Cross (z) = tangential share.  Dot = radial share; positive means closing in.
        val tangential = abs(vx * bz - vz * bx)
        val radial = vx * bx + vz * bz

        val omega = tangential / max(s.distance, 0.5)

        // Inside reach, closing in only enlarges the player's angular size — the one thing that
        // makes him easier to hit. Outside reach it is how he gets into range at all.
        val radialPenalty = if (s.distance <= s.reach && radial > 0.0) radial * 2.0 else 0.0

        return omega - radialPenalty
    }
}
