package io.doppel.core.strategy.tap

import io.doppel.core.model.Hitbox
import io.doppel.core.model.TargetState
import io.doppel.core.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StrafePatternTest {

    private fun targetAt(
        x: Double, z: Double, mx: Double = 0.0, mz: Double = 0.0
    ) = TargetState(
        entityId = 1, name = "T",
        posX = x, posY = 64.0, posZ = z,
        motionX = mx, motionY = 0.0, motionZ = mz,
        health = 20f, hurtTime = 0, distance = 3f,
        hitbox = Hitbox(x - 0.3, 64.0, z - 0.3, x + 0.3, 65.8, z + 0.3)
    )

    private val origin = Vec3(0.0, 64.0, 0.0)

    // ── wrap180 ──

    @Test
    fun wrap180_keepsInscribedAngles() {
        assertEquals(0f, StrafePattern.wrap180(0f))
        assertEquals(90f, StrafePattern.wrap180(90f))
        assertEquals(-90f, StrafePattern.wrap180(270f))
        assertEquals(-10f, StrafePattern.wrap180(350f))
        assertEquals(10f, StrafePattern.wrap180(-350f))
        assertEquals(-179f, StrafePattern.wrap180(181f))
    }

    // ── chaseSide geometry ──

    @Test
    fun chaseSide_targetOnLeft_returnsLeft() {
        // Player at origin facing +Z (yaw 0). MC: left hand points +X (east).
        // Target at (5, 10) is on the player's LEFT → tap A.
        assertEquals(Side.LEFT, StrafePattern.chaseSide(0f, origin, targetAt(5.0, 10.0), 0))
    }

    @Test
    fun chaseSide_targetOnRight_returnsRight() {
        // Target at (-5, 10) is on the player's RIGHT → tap D.
        assertEquals(Side.RIGHT, StrafePattern.chaseSide(0f, origin, targetAt(-5.0, 10.0), 0))
    }

    @Test
    fun chaseSide_motionLeadFlipsSide() {
        // Target slightly on the RIGHT, but sprinting hard toward the LEFT:
        // with enough lead ticks the predicted point crosses to the LEFT side.
        val drifting = targetAt(-1.0, 10.0, mx = 8.0, mz = 0.0) // moving +X (east/left of player)
        assertEquals(Side.LEFT, StrafePattern.chaseSide(0f, origin, drifting, 10))
        // With zero lead, the current position still decides → RIGHT.
        assertEquals(Side.RIGHT, StrafePattern.chaseSide(0f, origin, drifting, 0))
    }

    @Test
    fun chaseSide_respectsPlayerYaw() {
        // Player at origin facing -Z (yaw 180, north); left hand points -X (west).
        // Target at (-5, -10) is ahead-left → LEFT.
        assertEquals(Side.LEFT, StrafePattern.chaseSide(180f, origin, targetAt(-5.0, -10.0), 0))
        // Mirror: ahead-right → RIGHT.
        assertEquals(Side.RIGHT, StrafePattern.chaseSide(180f, origin, targetAt(5.0, -10.0), 0))
    }

    // ── 7-tap alternation ──

    @Test
    fun sevenTap_alternatesWithZeroRepeatChance() {
        val rng = Random(42)
        var side = Side.LEFT
        repeat(200) {
            val next = StrafePattern.sevenTapSide(side, repeatChancePercent = 0, rng = rng)
            // Deterministic: EVERY step must flip when repeatChance == 0.
            assertEquals(side.flipped(), next)
            side = next
        }
    }

    @Test
    fun sevenTap_repeatChanceProducesFumbles() {
        val rng = Random(7)
        var side = Side.LEFT
        var repeats = 0
        repeat(1000) {
            val prev = side
            side = StrafePattern.sevenTapSide(side, repeatChancePercent = 20, rng = rng)
            if (side == prev) repeats++ // fumble = same side as the previous step
        }
        // 20% fumble rate → ~200 repeats out of 1000 (binomial 3σ ≈ ±34).
        assertTrue(repeats in 120..280, "repeats=$repeats")
    }

    @Test
    fun randomSide_coversBothSides() {
        val rng = Random(99)
        var lefts = 0
        repeat(500) { if (StrafePattern.randomSide(rng) == Side.LEFT) lefts++ }
        assertTrue(lefts in 150..350, "lefts=$lefts")
    }

    // ── Side helpers ──

    @Test
    fun side_flipIsInvolutive() {
        assertEquals(Side.RIGHT, Side.LEFT.flipped())
        assertEquals(Side.LEFT, Side.RIGHT.flipped())
        assertEquals(Side.LEFT, Side.LEFT.flipped().flipped())
    }
}
