package io.doppel.core.algorithm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The aim step model, checked against the reference's numbers.
 *
 * Raven's smoothing: `t = speed/30`, `step = t^2 * 180` degrees per tick, capped by the distance left,
 * applied once to both axes. That produces 20 degrees per step at the default speed of 10, which is the
 * number these tests pin — if it drifts, the aim stops matching what the reference clients do and the
 * "too fast" it was changed for comes back.
 */
class AimStepScaleTest {

    @Test
    fun `speed ten steps twenty degrees`() {
        assertEquals(20f / 180f, RotationCalculator.stepScale(180f, 0f, 10, 1f), 0.0001f)
    }

    @Test
    fun `both axes share one scale, so the aim goes straight at the target`() {
        // delta (30, 40) has magnitude 50; a 20 degree step is 40% of it, and that one scale is what
        // both axes get. Easing the axes independently is what made it drift and feel unsteady.
        assertEquals(0.4f, RotationCalculator.stepScale(30f, 40f, 10, 1f), 0.0001f)
    }

    @Test
    fun `a step never overshoots what is left`() {
        assertEquals(1f, RotationCalculator.stepScale(5f, 0f, 10, 1f), 0.0001f)
        assertEquals(1f, RotationCalculator.stepScale(0.0001f, 0.0001f, 10, 1f), 0.0001f)
    }

    @Test
    fun `the ends of the scale mean what the reference says`() {
        assertEquals(1f, RotationCalculator.stepScale(37f, 12f, 30, 1f), 0.0001f, "30 = snap to the target")
        assertEquals(0f, RotationCalculator.stepScale(37f, 12f, 0, 1f), 0.0001f, "0 = do not move")
    }

    @Test
    fun `smoothness scales the step`() {
        assertEquals(10f / 180f, RotationCalculator.stepScale(180f, 0f, 10, 0.5f), 0.0001f)
    }
}
