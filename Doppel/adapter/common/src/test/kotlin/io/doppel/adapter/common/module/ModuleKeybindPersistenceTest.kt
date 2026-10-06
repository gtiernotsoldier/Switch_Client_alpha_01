package io.doppel.adapter.common.module

import io.doppel.adapter.common.option.ConfigManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A module's keybind has to survive a restart.
 *
 * It used to be a plain field on [Module], and only *registered options* are written to
 * `doppel-config.json` — so every key the user bound was silently forgotten, which is what "my
 * keybind stopped working" actually was. The keybind (and the red work-flash flag) are registered
 * as hidden options: persisted and validated like everything else, but kept out of the option list.
 */
class ModuleKeybindPersistenceTest {

    private class Binding : Module("Binding", Category.RENDER)

    /**
     * The persisted state is keyed by the module *name*, so every `Binding` in this class shares one
     * key — which is exactly the production behaviour (one instance per module) and exactly what
     * makes the tests order-dependent unless they reset it.
     */
    @org.junit.jupiter.api.BeforeEach
    fun resetState() {
        ConfigManager.set("Binding.${Module.KEYBIND_OPTION}", Module.NO_KEYBIND)
        ConfigManager.set("Binding.${Module.RED_INDICATOR_OPTION}", true)
    }

    @Test
    fun `a keybind survives a config round trip`() {
        val module = Binding()
        val file = File.createTempFile("doppel-keybind", ".json")
        try {
            module.keybind = 78 // GLFW 'N'
            assertEquals(78, module.keybind)

            ConfigManager.save(file)
            module.keybind = Module.NO_KEYBIND
            assertEquals(Module.NO_KEYBIND, module.keybind)

            ConfigManager.load(file)
            assertEquals(78, module.keybind, "the keybind is restored from the config file")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the red work-flash flag survives a config round trip`() {
        val module = Binding()
        val file = File.createTempFile("doppel-red", ".json")
        try {
            module.showRedIndicator = false
            ConfigManager.save(file)
            module.showRedIndicator = true
            ConfigManager.load(file)
            assertFalse(module.showRedIndicator, "a stealth module stays stealth after a restart")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the persisted state is registered but never offered as a tunable option`() {
        val module = Binding()
        val offered = ConfigManager.optionsOf(module.name).map { it.name }
        assertFalse(offered.contains(Module.KEYBIND_OPTION), "no Keybind slider in the panel")
        assertFalse(offered.contains(Module.RED_INDICATOR_OPTION), "no RedIndicator slider")

        val registered = ConfigManager.getRegisteredKeys()
        assertTrue(
            registered.contains("${module.name}.${Module.KEYBIND_OPTION}"),
            "…but it is registered, which is what makes it persist"
        )
        assertTrue(registered.contains("${module.name}.${Module.RED_INDICATOR_OPTION}"))
    }

    @Test
    fun `a fresh module is unbound and flashes red`() {
        val module = Binding()
        assertEquals(Module.NO_KEYBIND, module.keybind)
        assertTrue(module.showRedIndicator)
    }

    /// The keybind the panel posts is the GLFW code, and the poll compares the same code — one
    /// value, two readers, no translation in between.
    @Test
    fun `tryKeybindToggle matches the stored code and ignores the others`() {
        val module = Binding()
        module.keybind = 78
        assertFalse(module.tryKeybindToggle(77))
        assertFalse(module.enabled)
        assertTrue(module.tryKeybindToggle(78))
        assertTrue(module.enabled)
        assertTrue(module.tryKeybindToggle(78), "the same key toggles it back")
        assertFalse(module.enabled)
    }
}
