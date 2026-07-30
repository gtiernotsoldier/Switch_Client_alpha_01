package io.switchlite.core.option

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TriggerOptionsTest {

    @Test
    fun `default values are correct`() {
        val opts = TriggerOptions()
        assertFalse(opts.onlyGround)
        assertFalse(opts.onlyAir)
        assertEquals(0f, opts.minDistance)
        assertEquals(Float.MAX_VALUE, opts.maxDistance)
        assertEquals(100, opts.chance)
        assertEquals(0, opts.delayTicks)
        assertEquals(0, opts.delayMs)
        assertEquals(30.0f, opts.lookAngleThreshold)
    }

    @Test
    fun `builder default values match data class defaults`() {
        val built = TriggerOptions.Builder().build()
        val direct = TriggerOptions()
        assertEquals(direct, built)
    }

    @Test
    fun `builder sets all fields`() {
        val opts = TriggerOptions.Builder().apply {
            onlyGround = true
            chance = 50
            lookAngleThreshold = 45f
            minDistance = 2f
            maxDistance = 6f
        }.build()
        assertTrue(opts.onlyGround)
        assertEquals(50, opts.chance)
        assertEquals(45f, opts.lookAngleThreshold)
        assertEquals(2f, opts.minDistance)
        assertEquals(6f, opts.maxDistance)
    }

    @Test
    fun `data class equals works`() {
        val a = TriggerOptions(onlyGround = true, chance = 50)
        val b = a.copy()
        assertEquals(a, b)
    }

    @Test
    fun `data class copy keeps fields`() {
        val a = TriggerOptions(onlyGround = true, onlyMove = true, chance = 80)
        val b = a.copy(onlyGround = false)
        assertFalse(b.onlyGround)
        assertTrue(b.onlyMove)
        assertEquals(80, b.chance)
    }
}
