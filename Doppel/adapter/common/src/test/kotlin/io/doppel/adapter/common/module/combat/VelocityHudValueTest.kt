package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.option.ConfigManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Velocity's HUD row shows the ranges the user configured.
 *
 * It used to print the midpoint of each pair, so a 0%..100% horizontal range showed as "50%" — a
 * number the user never set, and one that disagreed with the two values the panel shows.
 */
class VelocityHudValueTest {

    private fun restore() {
        ConfigManager.set("Velocity.Mode", "Legit")
        ConfigManager.set("Velocity.HorizontalMin", 0.4f)
        ConfigManager.set("Velocity.HorizontalMax", 0.6f)
        ConfigManager.set("Velocity.VerticalMin", 0.4f)
        ConfigManager.set("Velocity.VerticalMax", 0.6f)
    }

    @AfterEach
    fun tearDown() = restore()

    @Test
    fun `identical ends collapse to one value`() {
        restore()
        // The default 40%..60% on both axes.
        assertEquals("Legit 40%-60%/40%-60%", Velocity.hudValue())
    }

    @Test
    fun `the user's example renders as ranges`() {
        // Velocity's own delegates register on first read.
        Velocity.hudValue()
        ConfigManager.set("Velocity.Mode", "Legit")
        ConfigManager.set("Velocity.HorizontalMin", 0.8f)
        ConfigManager.set("Velocity.HorizontalMax", 1.0f)
        ConfigManager.set("Velocity.VerticalMin", 1.0f)
        ConfigManager.set("Velocity.VerticalMax", 1.0f)

        assertEquals("Legit 80%-100%/100%", Velocity.hudValue())
    }

    @Test
    fun `an inverted pair still renders in order`() {
        Velocity.hudValue()
        // The option layer keeps pairs ordered, so this should already be 80/100 — but the formatter
        // must not depend on it to print the smaller number first.
        ConfigManager.set("Velocity.HorizontalMin", 0.8f)
        ConfigManager.set("Velocity.HorizontalMax", 1.0f)
        assertEquals(0.8f, ConfigManager.get<Float>("Velocity.HorizontalMin"))
        assertEquals(1.0f, ConfigManager.get<Float>("Velocity.HorizontalMax"))
        assertEquals("Legit 80%-100%/40%-60%", Velocity.hudValue())
    }

    @Test
    fun `click mode reports the click burst instead of the ranges it does not use`() {
        Velocity.hudValue()
        ConfigManager.set("Velocity.Mode", "Click")
        assertEquals("Click 2-5 clicks", Velocity.hudValue())
    }
}
