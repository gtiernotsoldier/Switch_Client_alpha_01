package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HUD v3 work-flash time math (Module.markWorked / flashStrength). */
class ModuleFlashTest {

    private class DummyModule : Module("Dummy", Category.RENDER)

    @Test
    fun `idle module has zero flash strength`() {
        val m = DummyModule()
        assertEquals(0f, m.flashStrength(), 0.0001f)
    }

    @Test
    fun `flash peaks right after markWorked and decays to zero after 420ms`() {
        val m = DummyModule()
        // markWorked uses the real clock; derive its write instant from strength math.
        m.markWorked()
        val now = System.currentTimeMillis()
        // Peak: still high right after the call (allow tiny scheduler slack).
        assertTrue(m.flashStrength(now) > 0.95f, "peak strength at t0, got ${m.flashStrength(now)}")
        // Linear decay: half-way at ~210ms.
        assertTrue(m.flashStrength(now + 200) in 0.45f..0.55f, "half-life at ~210ms, got ${m.flashStrength(now + 200)}")
        // Fully decayed at/past the 420ms horizon.
        assertEquals(0f, m.flashStrength(now + Module.WORK_FLASH_MS), 0.0001f)
        assertEquals(0f, m.flashStrength(now + 5_000), 0.0001f)
    }

    @Test
    fun `stealth module (showRedIndicator false) never flashes`() {
        val m = DummyModule()
        m.showRedIndicator = false
        m.markWorked()
        assertEquals(0f, m.flashStrength(), 0.0001f)
    }

    @Test
    fun `strength clamps to 1 even with generous duration`() {
        val m = DummyModule()
        m.markWorked(durationMs = 10_000)
        val now = System.currentTimeMillis()
        assertTrue(m.flashStrength(now) <= 1f && m.flashStrength(now) > 0.9f)
    }
}
