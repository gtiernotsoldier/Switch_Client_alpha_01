package io.switchlite.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Vec3Test {

    @Test fun `ZERO is correct`() { assertEquals(Vec3(0.0, 0.0, 0.0), Vec3.ZERO) }
    @Test fun `equals works`() { assertEquals(Vec3(1.0, 2.0, 3.0), Vec3(1.0, 2.0, 3.0)) }

    @Test
    fun `plus`() { assertEquals(Vec3(3.0, 5.0, 7.0), Vec3(1.0, 2.0, 3.0) + Vec3(2.0, 3.0, 4.0)) }

    @Test
    fun `minus`() { assertEquals(Vec3(1.0, 1.0, 1.0), Vec3(3.0, 4.0, 5.0) - Vec3(2.0, 3.0, 4.0)) }

    @Test
    fun `times scalar`() { assertEquals(Vec3(2.0, 4.0, 6.0), Vec3(1.0, 2.0, 3.0) * 2.0) }

    @Test
    fun `length of 3-4-5`() { assertEquals(5.0, Vec3(3.0, 4.0, 0.0).length(), 0.001) }

    @Test
    fun `normalize`() {
        val n = Vec3(3.0, 0.0, 4.0).normalize()
        assertEquals(1.0, n.length(), 0.001)
        assertEquals(0.6, n.x, 0.001)
    }

    @Test
    fun `dot product`() { assertEquals(32.0, Vec3(1.0, 2.0, 3.0).dot(Vec3(4.0, 5.0, 6.0)), 0.001) }

    @Test
    fun `cross product`() {
        val c = Vec3(1.0, 0.0, 0.0).cross(Vec3(0.0, 1.0, 0.0))
        assertEquals(0.0, c.x, 0.001)
        assertEquals(0.0, c.y, 0.001)
        assertEquals(1.0, c.z, 0.001)
    }

    @Test
    fun `distanceTo`() { assertEquals(5.0, Vec3(0.0, 0.0, 0.0).distanceTo(Vec3(3.0, 4.0, 0.0)), 0.001) }
}
