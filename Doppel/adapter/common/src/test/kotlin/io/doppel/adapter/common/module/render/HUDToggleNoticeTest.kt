package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pressing a bound key says so.
 *
 * A key press with no visible result is indistinguishable from a key press that never arrived, which
 * is why "my keybind does not work" was impossible to answer. The HUD now shows a short-lived line
 * naming the module and its new state — including for a module that just switched *off* and therefore
 * has no row of its own any more.
 */
class HUDToggleNoticeTest {

    private class Bound : Module("Bound", Category.VISUAL)

    @BeforeEach
    fun setUp() {
        if (ModuleRegistry.get(HUD.name) == null) ModuleRegistry.register(HUD)
        HUD.enable()
    }

    @AfterEach
    fun tearDown() {
        HUD.disable()
    }

    @Test
    fun `a notice names the module and says which way it went`() {
        HUD.noticeToggle("WTap", true)
        assertTrue(HUD.hudEntries.any { it.name == "WTap" && it.value == "ON" })

        HUD.noticeToggle("WTap", false)
        assertTrue(HUD.hudEntries.any { it.name == "WTap" && it.value == "OFF" })
    }

    @Test
    fun `the notice sits above the module rows and does not need the module listed`() {
        HUD.noticeToggle("NotEnabled", true)

        assertEquals("Doppel", HUD.hudEntries.first().name, "the title row stays first")
        assertEquals("NotEnabled", HUD.hudEntries[1].name, "the confirmation is the next line")
    }

    /**
     * The whole Kotlin path: a key code reaching `tryKeybindToggle` produces a visible line, which is
     * the half of "does the keybind work" the HUD can answer on its own.
     */
    @Test
    fun `a keybind toggle reaches the HUD through EventBridge`() {
        val module = Bound()
        if (ModuleRegistry.get(module.name) == null) ModuleRegistry.register(module)
        module.keybind = 78 // GLFW 'N'

        assertTrue(module.tryKeybindToggle(78), "the bound code toggles")
        assertTrue(
            HUD.hudEntries.any { it.name == "Bound" && it.value == "ON" },
            "and the HUD says so: ${HUD.hudEntries.map { "${it.name}=${it.value}" }}"
        )

        assertTrue(module.tryKeybindToggle(78))
        assertTrue(HUD.hudEntries.any { it.name == "Bound" && it.value == "OFF" })
    }

    @Test
    fun `a key that is not this module's does nothing at all`() {
        val module = Bound()
        module.keybind = 78
        assertEquals(false, module.tryKeybindToggle(77))
        assertTrue(HUD.hudEntries.none { it.name == "Bound" })
    }

    @Test
    fun `the notice handler is cleared when the HUD is disabled`() {
        HUD.disable()
        // Nothing to draw into, and with no handler registered the notice would be a silent no-op on
        // the next key press rather than an exception.
        EventBridge.noticeModuleToggled("Bound", true)
        assertTrue(HUD.hudEntries.isEmpty())
    }
}
