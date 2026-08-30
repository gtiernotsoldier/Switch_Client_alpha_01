package io.doppel.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MathUtilsTest {

    @Test fun `clamp - within range`() { assertEquals(5f, MathUtils.clamp(5f, 0f, 10f)) }
    @Test fun `clamp - below min`() { assertEquals(0f, MathUtils.clamp(-5f, 0f, 10f)) }
    @Test fun `clamp - above max`() { assertEquals(10f, MathUtils.clamp(15f, 0f, 10f)) }
    @Test fun `clamp - at boundary`() { assertEquals(0f, MathUtils.clamp(0f, 0f, 10f)) }

    @Test fun `clamp double - within range`() { assertEquals(5.0, MathUtils.clamp(5.0, 0.0, 10.0)) }
    @Test fun `clamp double - below min`() { assertEquals(0.0, MathUtils.clamp(-5.0, 0.0, 10.0)) }

    @Test fun `lerp - factor 0`() { assertEquals(0f, MathUtils.lerp(0f, 100f, 0f)) }
    @Test fun `lerp - factor 1`() { assertEquals(100f, MathUtils.lerp(0f, 100f, 1f)) }
    @Test fun `lerp - factor 05`() { assertEquals(50f, MathUtils.lerp(0f, 100f, 0.5f), 0.01f) }

    @Test fun `lerp double - midpoint`() { assertEquals(50.0, MathUtils.lerp(0.0, 100.0, 0.5), 0.01) }

    @Test
    fun `randomFloat stays within range`() {
        repeat(100) { val v = MathUtils.randomFloat(2f, 5f); assertTrue(v in 2f..5f) }
    }

    @Test
    fun `randomInt stays within range and is inclusive`() {
        repeat(100) { val v = MathUtils.randomInt(1, 3); assertTrue(v in 1..3) }
    }
}
