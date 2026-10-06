package io.doppel.adapter.common.module.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A4 — the Keystrokes Rainbow colour must not run AWT inside MC's render loop.
 *
 * It used to call `java.awt.Color.getHSBColor` every frame. It is now a local HSV→RGB plus a 50 ms
 * cache, so these tests pin the replacement against AWT's own arithmetic across the whole cycle:
 * the rainbow has to look *exactly* as it did, or the fix is a visible regression.
 */
class KeystrokesColorTest {

    @Test
    fun `local hsv matches java awt across the rainbow cycle`() {
        // The production hues are (now % 3750) / 3750, so sample every step of that cycle plus the
        // segment boundaries where an off-by-one in the HSV sector table would show up.
        val hues = buildList {
            for (i in 0 until 3750) add(i.toFloat() / 3750f)
            add(0f); add(1f / 6f); add(2f / 6f); add(3f / 6f); add(4f / 6f); add(5f / 6f)
            add(0.999999f)
        }

        for (hue in hues) {
            val awt = java.awt.Color.getHSBColor(hue, 1f, 1f).rgb
            val local = Keystrokes.hsvToRgb(hue, 1f, 1f)
            assertEquals(awt, local, "hue=$hue must match java.awt.Color.getHSBColor")
        }
    }

    @Test
    fun `hsv keeps the generic saturation and value behaviour`() {
        // Not just the s=v=1 rainbow path: getHSBColor is the general formula and the port must be
        // a faithful one in case a future option exposes saturation/value.
        for (h in 0..20) {
            val hue = h / 20f
            for (s in intArrayOf(0, 64, 128, 255)) {
                for (v in intArrayOf(0, 64, 128, 255)) {
                    val sat = s / 255f
                    val value = v / 255f
                    assertEquals(
                        java.awt.Color.getHSBColor(hue, sat, value).rgb,
                        Keystrokes.hsvToRgb(hue, sat, value),
                        "h=$hue s=$sat v=$value"
                    )
                }
            }
        }
    }

    @Test
    fun `rainbow colour is cached instead of recomputed per frame`() {
        val first = Keystrokes.rainbowColor()
        // Back-to-back calls are inside the 50 ms refresh window, so they must return the identical
        // cached value — that is the whole point: no work on the render path.
        val second = Keystrokes.rainbowColor()
        assertEquals(first, second, "two calls inside the refresh window must share the cache")

        // It is still a real colour (opaque, in range), not a degenerate black.
        assertTrue(first ushr 24 == 0xFF, "alpha must be opaque")
        assertTrue((first and 0xFFFFFF) != 0, "rainbow must not be black")
    }
}
