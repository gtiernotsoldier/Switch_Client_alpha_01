package io.doppel.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Vec2Test {

    @Test fun `equals works`() { assertEquals(Vec2(0f, 0f), Vec2(0f, 0f)) }
    @Test fun `copy works`() {
        val a = Vec2(10f, 20f)
        assertEquals(Vec2(5f, 20f), a.copy(yaw = 5f))
    }
    @Test fun `component access`() {
        val v = Vec2(45f, -10f)
        assertEquals(45f, v.yaw)
        assertEquals(-10f, v.pitch)
    }
    @Test fun `ZERO is correct`() {
        assertEquals(Vec2(0f, 0f), Vec2.ZERO)
    }
}
