package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The HUD row carries the module's bound key.
 *
 * The user could not tell from the HUD (or the panel) whether a keybind had taken, so a bound
 * module's row now ends in `[N]` and an unbound one simply shows its value.
 */
class HUDKeybindValueTest {

    private class Dummy : Module("Dummy", Category.RENDER)

    /**
     * The keybind lives in the config store, keyed by the module *name* — which is what makes it
     * persist, and also what makes every `Dummy` in this class share one key. Reset it per test so
     * the cases stay independent.
     */
    @org.junit.jupiter.api.BeforeEach
    fun resetKeybind() {
        io.doppel.adapter.common.option.ConfigManager.set("Dummy.Keybind", Module.NO_KEYBIND)
    }

    @Test
    fun `an unbound module shows its value alone`() {
        val m = Dummy()
        assertEquals(-1, m.keybind, "modules start unbound")
        assertEquals("40 ms", HUD.withKeybind(m, "40 ms"))
    }

    @Test
    fun `a bound module shows the key in brackets`() {
        val m = Dummy()
        m.keybind = 78 // GLFW 'N' — the user's own example
        assertEquals("40 ms[N]", HUD.withKeybind(m, "40 ms"))
        assertEquals("Legit 80%-100%/100%[N]", HUD.withKeybind(m, "Legit 80%-100%/100%"))
    }

    @Test
    fun `a module with no value shows only the key`() {
        val m = Dummy()
        m.keybind = 290 // F1
        assertEquals("[F1]", HUD.withKeybind(m, ""))
    }

    @Test
    fun `clearing the binding removes the key again`() {
        val m = Dummy()
        m.keybind = 78
        assertEquals("40 ms[N]", HUD.withKeybind(m, "40 ms"))
        m.keybind = -1
        assertEquals("40 ms", HUD.withKeybind(m, "40 ms"))
    }
}
