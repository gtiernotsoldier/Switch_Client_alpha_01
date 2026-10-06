package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The user's manual HUD order — what the WebUI's up/down buttons drive and what the overlay draws
 * when the header's `orderPublished` flag is set.
 *
 * The order is deliberately held as a list of *names* independent of the module objects: a module
 * that is switched off keeps its slot, and a module that has never been listed keeps its own
 * registration order behind the ones the user placed.
 */
class HUDOrderTest {

    private class Dummy(name: String) : Module(name, Category.RENDER)

    private val names = listOf("Alpha", "Bravo", "Charlie")

    /**
     * The order of the three dummies, ignoring anything else the registry happens to hold.
     *
     * `hudOrderedNames()` lists every registered module, and other test classes in this JVM
     * register their own — so the assertion is about relative order, not about the whole registry.
     */
    private fun orderedDummies(): List<String> = HUD.hudOrderedNames().filter { it in names }

    @BeforeEach
    fun setUp() {
        // Test-only dummies: the adapter never registers these names, and nothing in this suite
        // enables them, so they cannot leak into another test's HUD rows.
        names.forEach { name ->
            if (ModuleRegistry.get(name) == null) ModuleRegistry.register(Dummy(name))
        }
        HUD.hudOrderRaw = ""
    }

    @AfterEach
    fun tearDown() {
        HUD.hudOrderRaw = ""
    }

    @Test
    fun `with no manual order the registry order stands`() {
        assertEquals(names, orderedDummies())
        assertEquals(0, HUD.hudIndexOf("Alpha"))
        assertEquals(2, HUD.hudIndexOf("Charlie"))
    }

    @Test
    fun `moving a module up swaps it with the one above`() {
        assertTrue(HUD.moveInHud("Charlie", "up"))

        assertEquals(listOf("Alpha", "Charlie", "Bravo"), orderedDummies())
        assertEquals(1, HUD.hudIndexOf("Charlie"))
        assertEquals(2, HUD.hudIndexOf("Bravo"))
    }

    @Test
    fun `moving a module down swaps it with the one below`() {
        assertTrue(HUD.moveInHud("Alpha", "down"))

        assertEquals(listOf("Bravo", "Alpha", "Charlie"), orderedDummies())
    }

    @Test
    fun `the ends are clamped rather than wrapped`() {
        assertFalse(HUD.moveInHud("Alpha", "up"), "the first row cannot move up")
        assertFalse(HUD.moveInHud("Charlie", "down"), "the last row cannot move down")
        assertEquals(names, orderedDummies(), "a refused move changes nothing")
    }

    @Test
    fun `an unknown module and an unknown direction are refused`() {
        assertFalse(HUD.moveInHud("NotAModule", "up"))
        assertFalse(HUD.moveInHud("Alpha", "sideways"))
        assertEquals(names, orderedDummies())
    }

    @Test
    fun `an order survives a round trip through the stored string`() {
        HUD.moveInHud("Charlie", "up")
        val stored = HUD.hudOrderRaw
        assertTrue(stored.contains("Charlie"), "the order is persisted as a name list: $stored")

        // Re-reading the stored value must reproduce the same order — the WebUI saves it as a plain
        // option, so it has to survive a config reload unchanged.
        HUD.hudOrderRaw = ""
        assertEquals(names, orderedDummies())
        HUD.hudOrderRaw = stored
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), orderedDummies())
    }
}
