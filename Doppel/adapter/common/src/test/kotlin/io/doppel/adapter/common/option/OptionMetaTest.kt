package io.doppel.adapter.common.option

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * B2 — one precision rule for numeric options.
 *
 * The WebUI used to round every float to a single decimal on its own, and the HUD published the
 * raw `Float.toString()`, so the panel and the external overlay could show different numbers for
 * the same option. Both now derive from [OptionMeta.decimals], which this class pins.
 */
class OptionMetaTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun float(default: Float, step: Double = 0.0) = OptionMeta(
        type = OptionType.FLOAT, default = default, rangeMin = 0f, rangeMax = 1f, step = step
    )

    private fun int(default: Int, step: Double = 0.0) = OptionMeta(
        type = OptionType.INT, default = default, intRangeMin = 0, intRangeMax = 100, step = step
    )

    @Test
    fun `an undeclared float falls back to the type default of two decimals`() {
        val meta = float(0.4f)
        assertEquals(0.01, meta.effectiveStep()!!, 1e-12)
        assertEquals(2, meta.decimals())
        // The old panel showed "0.4" and the HUD published "0.4"; both now show the same two-decimal
        // text, so a value like 0.35 can no longer read as "0.4" in one place and "0.35" in another.
        assertEquals("0.40", meta.formatNumber(0.4f))
        assertEquals("0.35", meta.formatNumber(0.35f))
        assertEquals("1.00", meta.formatNumber(1.0f))
    }

    @Test
    fun `a declared step sets the precision`() {
        assertEquals(2, float(0.35f, step = 0.05).decimals())
        assertEquals(3, float(0.25f, step = 0.001).decimals())
        assertEquals(1, float(0.5f, step = 0.5).decimals())
        assertEquals(0, float(1.0f, step = 1.0).decimals())

        assertEquals("0.35", float(0.35f, step = 0.05).formatNumber(0.35))
        assertEquals("0.250", float(0.25f, step = 0.001).formatNumber(0.25))
        assertEquals("1", float(1.0f, step = 1.0).formatNumber(1.0))
    }

    @Test
    fun `integers keep zero decimals whatever their step`() {
        assertEquals(1.0, int(12).effectiveStep()!!, 1e-12)
        assertEquals(0, int(12).decimals())
        assertEquals("12", int(12).formatNumber(12))
        assertEquals("10", int(10, step = 5.0).formatNumber(10))

        val probability = OptionMeta(
            type = OptionType.PROBABILITY, default = 100, intRangeMin = 0, intRangeMax = 100
        )
        assertEquals(1.0, probability.effectiveStep()!!, 1e-12)
        assertEquals(0, probability.decimals())
        assertEquals("100", probability.formatNumber(100))
    }

    @Test
    fun `non-numeric options declare no precision`() {
        val bool = OptionMeta(type = OptionType.BOOLEAN, default = true)
        val choices = OptionMeta(type = OptionType.CHOICES, default = "a", choices = arrayOf("a", "b"))
        val colour = OptionMeta(type = OptionType.COLOR, default = 0x4E566B, intRangeMin = 0, intRangeMax = COLOR_MAX)

        for (meta in listOf(bool, choices, colour)) {
            assertNull(meta.effectiveStep(), "$meta has no step")
            assertNull(meta.decimals(), "$meta has no precision")
        }
        assertEquals("1", bool.formatNumber(1), "falls back to plain toString with no declared step")
    }

    @Test
    fun `formatting is locale independent`() {
        // A comma decimal separator would break both the panel's parse and the config JSON.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("0.40", float(0.4f).formatNumber(0.4f))
        assertEquals("12", int(12).formatNumber(12))
    }
}
