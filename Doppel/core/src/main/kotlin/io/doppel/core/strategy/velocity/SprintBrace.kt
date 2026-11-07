package io.doppel.core.strategy.velocity

import io.doppel.contract.util.Vec3
import kotlin.math.sqrt
import kotlin.random.Random
import io.doppel.contract.math.PortableMath
import io.doppel.contract.strategy.velocity.VelocityMode

/** Which movement keys one brace tick should hold. */
data class BraceKeys(
    val forward: Boolean,
    val back: Boolean,
    val left: Boolean,
    val right: Boolean
) {
    val any: Boolean get() = forward || back || left || right

    companion object {
        val NONE = BraceKeys(forward = false, back = false, left = false, right = false)
    }
}

/** Unit vector pointing at the knockback SOURCE — i.e. back at the attacker. */
data class BraceSource(val x: Double, val z: Double)

/**
 * The arithmetic behind [io.doppel.contract.strategy.velocity.VelocityMode.SPRINT].
 *
 * Pure and platform-free: no `EventBridge`, no key names, no packets. The adapter decides *when* to
 * ask and *what* to do with the answer; everything numeric lives here, which is what the module's
 * "algorithm in Core" rule requires. The module previously held all of this inline, including the
 * yaw basis and the gate roll.
 */
object SprintBrace {

    /** Below this horizontal speed there is no meaningful direction to brace against. */
    private const val MIN_HORIZONTAL = 1e-4

    /**
     * The direction a brace should push in.
     *
     * Vanilla `knockBack()` does `motion /= 2` and *then* subtracts the knockback, so the velocity
     * you already had is half of your fate — the cancelling direction is the one the knockback came
     * from. Returns null for a (near) pure-vertical knockback, which has no source direction.
     */
    fun sourceFrom(motion: Vec3): BraceSource? {
        val h = sqrt(motion.x * motion.x + motion.z * motion.z)
        if (h < MIN_HORIZONTAL) return null
        return BraceSource(-motion.x / h, -motion.z / h)
    }

    /**
     * Resolve [source] against the player's facing into movement keys.
     *
     * [sector] is the cosine of the half-angle of each axis' press cone (cos 67.5° ≈ 0.38), so a
     * direction more than ~22.5° off an axis still gets a press and a diagonal gets two.
     */
    fun keysFor(yawDegrees: Float, source: BraceSource, sector: Double): BraceKeys {
        val yaw = PortableMath.toRadians(yawDegrees.toDouble())
        val fwdX = -PortableMath.sin(yaw)
        val fwdZ = PortableMath.cos(yaw)
        // Right of forward in the (x, z) plane: (x, z) -> (-z, x).
        val rgtX = -fwdZ
        val rgtZ = fwdX
        val fDot = fwdX * source.x + fwdZ * source.z
        val rDot = rgtX * source.x + rgtZ * source.z
        return BraceKeys(
            forward = fDot > sector,
            back = fDot < -sector,
            left = rDot < -sector,
            right = rDot > sector
        )
    }

    /**
     * The two-state gate: P(brace | braced last hit) and P(brace | did not).
     *
     * Equal values make it memoryless, which is the shape the human sprint-reset population has —
     * measured against it, a memoryless gate scores run-length D ≈ 0.01 while a deterministic
     * cadence ("every Nth hit") scores ≈ 0.2. The caller owns the state and feeds it back in.
     */
    fun gateRoll(rng: Random, lastPassed: Boolean, pBraced: Int, pIdle: Int): Boolean {
        val p = if (lastPassed) pBraced else pIdle
        return rng.nextInt(100) < p
    }
}
