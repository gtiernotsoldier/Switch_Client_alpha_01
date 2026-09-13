package io.doppel.adapter.common.hud

import io.doppel.adapter.common.ui.Theme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HUD v3 spine-gradient color math (Theme.lerpArgb / spineColor). */
class ThemeColorMathTest {

    @Test
    fun `lerp endpoints are exact`() {
        val a = 0xFF22D3EE.toInt()
        val b = 0xFFA78BFA.toInt()
        assertEquals(a, Theme.lerpArgb(a, b, 0f))
        assertEquals(b, Theme.lerpArgb(a, b, 1f))
    }

    @Test
    fun `lerp is monotonic per channel and alpha-preserving`() {
        val a = 0xFF22D3EE.toInt()
        val b = 0xFFA78BFA.toInt()
        var prevR = (a shr 16) and 0xFF
        var prevB = a and 0xFF
        for (step in 1..10) {
            val c = Theme.lerpArgb(a, b, step / 10f)
            assertEquals(0xFF, (c ushr 24) and 0xFF, "alpha must stay 0xFF")
            val r = (c shr 16) and 0xFF
            val bl = c and 0xFF
            assertTrue(r >= prevR, "R must be non-decreasing cyan→violet")
            assertTrue(bl >= prevB, "B must be non-decreasing")
            prevR = r; prevB = bl
        }
    }

    @Test
    fun `lerp clamps out-of-range t`() {
        val a = 0xFF102030.toInt()
        val b = 0xFF406080.toInt()
        assertEquals(a, Theme.lerpArgb(a, b, -5f))
        assertEquals(b, Theme.lerpArgb(a, b, 5f))
    }

    @Test
    fun `spine runs accent to accent2 across the list`() {
        val n = 8
        assertEquals(Theme.ACCENT, Theme.spineColor(0, n))
        assertEquals(Theme.ACCENT2, Theme.spineColor(n - 1, n))
        // Middle rows interpolate strictly between the two accents.
        val mid = Theme.spineColor(3, n)
        val midR = (mid shr 16) and 0xFF
        assertTrue(midR > ((Theme.ACCENT shr 16) and 0xFF) && midR < ((Theme.ACCENT2 shr 16) and 0xFF))
    }

    @Test
    fun `single-row list uses pure accent (no division by zero)`() {
        assertEquals(Theme.ACCENT, Theme.spineColor(0, 1))
        assertEquals(Theme.ACCENT, Theme.spineColor(0, 0))
    }
}
