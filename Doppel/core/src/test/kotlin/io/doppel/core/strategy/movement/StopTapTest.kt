package io.doppel.core.strategy.movement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The projection that decides when a brake tap has done its job.
 *
 * It has to use the player's *current* yaw: the direction that was released is "forward", and
 * forward moves when the player turns.
 */
class StopTapTest {

    @Test
    fun `sliding forward still brakes`() {
        assertTrue(StopTap.stillSliding(0.0, 0.3, 0f, releasedForward = true))
    }

    @Test
    fun `already moving backwards does not`() {
        assertFalse(StopTap.stillSliding(0.0, -0.3, 0f, releasedForward = true))
    }

    @Test
    fun `the released direction follows the yaw`() {
        // Facing -Z (yaw 180) and still moving +Z: that is backwards, not forwards.
        assertFalse(StopTap.stillSliding(0.0, 0.3, 180f, releasedForward = true))
        // Facing -X (yaw 90) and moving -X.
        assertTrue(StopTap.stillSliding(-0.3, 0.0, 90f, releasedForward = true))
    }

    @Test
    fun `releasing S brakes the other way`() {
        assertTrue(StopTap.stillSliding(0.0, -0.3, 0f, releasedForward = false))
        assertFalse(StopTap.stillSliding(0.0, 0.3, 0f, releasedForward = false))
    }

    @Test
    fun `speed below the threshold is not worth a key press`() {
        assertFalse(StopTap.stillSliding(0.0, 0.01, 0f, releasedForward = true))
        assertTrue(StopTap.stillSliding(0.0, 0.01, 0f, releasedForward = true, minSpeed = 0.0))
    }
}
