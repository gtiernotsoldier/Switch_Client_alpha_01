package io.doppel.adapter.common.module.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Freelook's angle arithmetic: the part that decides where the camera ends up. */
class FreelookTest {

    @Test
    fun `the mouse moves the camera by the sensitivity`() {
        val (yaw, _) = Freelook.accumulate(0f, 0f, 100f, 0f, 0.15f, invertY = false)
        assertEquals(15f, yaw, 0.001f)
    }

    @Test
    fun `pitch is clamped so the camera cannot flip over`() {
        val (_, up) = Freelook.accumulate(0f, 0f, 0f, -10000f, 0.15f, invertY = false)
        assertEquals(90f, up, 0.001f)
        val (_, down) = Freelook.accumulate(0f, 0f, 0f, 10000f, 0.15f, invertY = false)
        assertEquals(-90f, down, 0.001f)
    }

    @Test
    fun `invert y flips only the pitch`() {
        val normal = Freelook.accumulate(0f, 0f, 50f, 50f, 0.15f, invertY = false)
        val inverted = Freelook.accumulate(0f, 0f, 50f, 50f, 0.15f, invertY = true)
        assertEquals(normal.first, inverted.first, 0.001f)
        assertTrue(inverted.second > normal.second, "inverted must look the other way")
    }

    @Test
    fun `no mouse movement leaves the angles alone`() {
        val (yaw, pitch) = Freelook.accumulate(123f, -45f, 0f, 0f, 0.15f, invertY = false)
        assertEquals(123f, yaw, 0.001f)
        assertEquals(-45f, pitch, 0.001f)
    }
}
