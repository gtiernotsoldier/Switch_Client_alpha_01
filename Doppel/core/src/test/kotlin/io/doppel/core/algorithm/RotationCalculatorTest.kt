package io.doppel.core.algorithm

import io.doppel.core.util.Vec2
import io.doppel.core.util.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RotationCalculatorTest {

    // ── normalizeAngle ──

    @Test fun `normalizeAngle - 0 stays 0`() { assertEquals(0f, RotationCalculator.normalizeAngle(0f)) }
    @Test fun `normalizeAngle - 180 stays 180`() { assertEquals(180f, RotationCalculator.normalizeAngle(180f)) }
    @Test fun `normalizeAngle - 181 wraps to -179`() { assertEquals(-179f, RotationCalculator.normalizeAngle(181f)) }
    @Test fun `normalizeAngle - -181 wraps to 179`() { assertEquals(179f, RotationCalculator.normalizeAngle(-181f)) }
    @Test fun `normalizeAngle - 360 wraps to 0`() { assertEquals(0f, RotationCalculator.normalizeAngle(360f)) }
    @Test fun `normalizeAngle - -90 stays -90`() { assertEquals(-90f, RotationCalculator.normalizeAngle(-90f)) }
    @Test fun `normalizeAngle - 540 wraps to 180`() { assertEquals(180f, RotationCalculator.normalizeAngle(540f)) }

    // ── calculateDifference ──

    @Test
    fun `calculateDifference - same rotation`() {
        assertEquals(Vec2(0f, 0f), RotationCalculator.calculateDifference(Vec2(45f, 10f), Vec2(45f, 10f)))
    }

    @Test
    fun `calculateDifference - wraps around`() {
        val diff = RotationCalculator.calculateDifference(Vec2(-170f, 0f), Vec2(170f, 0f))
        assertEquals(-20f, diff.yaw, 0.1f)
    }

    @Test
    fun `calculateDifference - straight ahead`() {
        val diff = RotationCalculator.calculateDifference(Vec2(0f, 0f), Vec2(10f, 5f))
        assertEquals(10f, diff.yaw, 0.1f)
        assertEquals(5f, diff.pitch, 0.1f)
    }

    // ── isWithinFov ──

    @Test
    fun `isWithinFov - within bounds`() {
        assertTrue(RotationCalculator.isWithinFov(Vec2(20f, 5f), 90f, 30f))
    }

    @Test
    fun `isWithinFov - outside bounds`() {
        assertFalse(RotationCalculator.isWithinFov(Vec2(50f, 20f), 90f, 30f))
    }

    @Test
    fun `isWithinFov - exactly at boundary`() {
        // horizontalFov=90 → half=45, boundary at yaw=45
        assertTrue(RotationCalculator.isWithinFov(Vec2(45f, 0f), 90f, 30f))
        assertFalse(RotationCalculator.isWithinFov(Vec2(45.01f, 0f), 90f, 30f))
    }

    // ── interpolate ──

    @Test
    fun `interpolate - factor 0 returns current`() {
        assertEquals(Vec2(0f, 0f), RotationCalculator.interpolate(Vec2(0f, 0f), Vec2(90f, 45f), 0f))
    }

    @Test
    fun `interpolate - factor 1 returns target`() {
        assertEquals(Vec2(90f, 45f), RotationCalculator.interpolate(Vec2(0f, 0f), Vec2(90f, 45f), 1f))
    }

    @Test
    fun `interpolate - factor 05 returns midpoint`() {
        val result = RotationCalculator.interpolate(Vec2(0f, 0f), Vec2(100f, 50f), 0.5f)
        assertEquals(50f, result.yaw, 0.1f)
        assertEquals(25f, result.pitch, 0.1f)
    }

    // ── yawToDirection ──

    @Test
    fun `yawToDirection - yaw 0 faces south`() {
        val dir = RotationCalculator.yawToDirection(0f)
        assertEquals(0.0, dir.x, 0.001)
        assertTrue(dir.z > 0)
        assertEquals(0.0, dir.y, 0.001)
    }

    @Test
    fun `yawToDirection - yaw 90 faces west`() {
        val dir = RotationCalculator.yawToDirection(90f)
        assertEquals(-1.0, dir.x, 0.001)
        assertEquals(0.0, dir.z, 0.001)
    }

    @Test
    fun `yawToDirection - yaw -90 faces east`() {
        val dir = RotationCalculator.yawToDirection(-90f)
        assertEquals(1.0, dir.x, 0.001)
        assertEquals(0.0, dir.z, 0.001)
    }

    // ── calculateRotation ──

    @Test
    fun `calculateRotation - target directly south`() {
        val rot = RotationCalculator.calculateRotation(Vec3(0.0, 64.0, 0.0), Vec3(0.0, 64.0, 5.0))
        assertEquals(0f, rot.yaw, 1f)
    }

    @Test
    fun `calculateRotation - target above`() {
        val rot = RotationCalculator.calculateRotation(Vec3(0.0, 64.0, 0.0), Vec3(0.0, 69.0, 5.0))
        assertTrue(rot.pitch < 0)  // negative pitch = looking up (MC convention)
    }
}
