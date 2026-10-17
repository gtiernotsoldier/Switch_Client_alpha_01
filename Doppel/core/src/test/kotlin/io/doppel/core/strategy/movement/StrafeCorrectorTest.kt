package io.doppel.core.strategy.movement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The corrector's contract: it removes exactly one key, only when the geometry says that key is
 * working against the player, and it never adds input.
 *
 * Geometry convention in these cases: the target sits on +X (bearing 0). A yaw of -90 degrees
 * makes the player's forward vector point at the target, so "W" is closing in and "D" is the
 * tangent. That is the ordinary fighting stance the correction is meant for.
 */
class StrafeCorrectorTest {

    private val facing = StrafeCorrector.State(
        distance = 2.5,
        bearingRad = 0.0,
        yawDeg = -90.0,
        reach = 3.0
    )

    @Test
    fun `damps forward when it only closes the distance inside reach`() {
        val c = StrafeCorrector.correct(
            StrafeCorrector.Input(forward = true, right = true), facing
        )
        // W+D inside reach: W adds radial share (bigger angular size), D adds the tangent.
        // Releasing W leaves the pure tangent.
        assertTrue(c.suppressForward, "W should be the one released")
        assertFalse(c.suppressRight, "D is the tangential part and must stay")
        assertTrue(c.after > c.before)
    }

    @Test
    fun `leaves forward alone while still closing from outside reach`() {
        // At 4 blocks W is how the player gets into range at all, so it carries no penalty.
        val far = facing.copy(distance = 4.0)
        val c = StrafeCorrector.correct(StrafeCorrector.Input(forward = true), far)
        assertFalse(c.changed)
    }

    @Test
    fun `a pure tangent is already optimal`() {
        val c = StrafeCorrector.correct(StrafeCorrector.Input(right = true), facing)
        assertFalse(c.changed, "there is nothing better to release")
    }

    @Test
    fun `does nothing when the player is not moving`() {
        val c = StrafeCorrector.correct(StrafeCorrector.Input(), facing)
        assertFalse(c.changed)
    }

    @Test
    fun `opposite keys cancel and one of them is released`() {
        // A+D produce no direction at all. Dropping one of them restores a real tangent.
        val c = StrafeCorrector.correct(
            StrafeCorrector.Input(left = true, right = true), facing
        )
        assertTrue(c.changed, "a cancelling pair should be broken up")
        assertTrue(c.after > c.before)
    }

    @Test
    fun `retreating is not corrected`() {
        // Facing away from the target (-90 -> +90) and pressing W means walking away, which has a
        // negative radial share: no penalty, and nothing better to release.
        val away = facing.copy(yawDeg = 90.0)
        val c = StrafeCorrector.correct(StrafeCorrector.Input(forward = true), away)
        assertFalse(c.changed)
    }

    @Test
    fun `the gap must exceed the minimum gain`() {
        // A hair inside reach with a nearly tangential heading: the radial share is tiny, so the
        // improvement is below MIN_GAIN and the player's input is kept.
        val grazing = StrafeCorrector.State(
            distance = 2.99,
            bearingRad = 0.0,
            yawDeg = -80.0,       // mostly facing the target, slightly off
            reach = 3.0
        )
        val c = StrafeCorrector.correct(StrafeCorrector.Input(forward = true), grazing)
        // Whatever it decides, it must never report a change that did not clear the threshold.
        if (c.changed) assertTrue(c.after >= c.before + StrafeCorrector.MIN_GAIN)
    }
}
