package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.option.ConfigManager
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
 *
 * Every assertion here is about the three dummies' order **relative to each other**: the registry is
 * process-wide and other test classes in this JVM register their own modules, so a test that assumed
 * it was alone in the list passed only while it happened to run first.
 */
class HUDOrderTest {

    private class Dummy(name: String) : Module(name, Category.RENDER)

    private val names = listOf("Alpha", "Bravo", "Charlie")

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

    /** The three dummies' order, ignoring whatever else the registry holds. */
    private fun orderedDummies(): List<String> = HUD.hudOrderedNames().filter { it in names }

    /** Pin the arrangement so the dummies are the whole arranged set, whatever else is registered. */
    private fun arrangeDummies() {
        HUD.hudOrderRaw = names.joinToString(",")
    }

    @Test
    fun `with no manual order the three keep their registration order`() {
        assertEquals(names, orderedDummies())
        assertTrue(HUD.hudIndexOf("Alpha") < HUD.hudIndexOf("Bravo"))
        assertTrue(HUD.hudIndexOf("Bravo") < HUD.hudIndexOf("Charlie"))
    }

    @Test
    fun `moving a module up swaps it with the one above`() {
        arrangeDummies()
        assertTrue(HUD.moveInHud("Charlie", "up"))

        assertEquals(listOf("Alpha", "Charlie", "Bravo"), orderedDummies())
        assertTrue(HUD.hudIndexOf("Charlie") < HUD.hudIndexOf("Bravo"))
    }

    @Test
    fun `moving a module down swaps it with the one below`() {
        arrangeDummies()
        assertTrue(HUD.moveInHud("Alpha", "down"))

        assertEquals(listOf("Bravo", "Alpha", "Charlie"), orderedDummies())
    }

    @Test
    fun `the ends of the list are clamped rather than wrapped`() {
        // Asked of the list's real ends, not of the dummies: the registry holds other modules too,
        // and the contract is about the position, not about which module occupies it.
        val first = HUD.hudOrderedNames().first()
        val last = HUD.hudOrderedNames().last()

        assertFalse(HUD.moveInHud(first, "up"), "the first row cannot move up")
        assertFalse(HUD.moveInHud(last, "down"), "the last row cannot move down")
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
        arrangeDummies()
        assertTrue(HUD.moveInHud("Charlie", "up"))
        val stored = HUD.hudOrderRaw
        assertTrue(stored.contains("Charlie"), "the order is persisted as a name list: $stored")

        // Re-reading the stored value must reproduce the same order — the WebUI saves it as a plain
        // option, so it has to survive a config reload unchanged.
        HUD.hudOrderRaw = ""
        assertEquals(names, orderedDummies())
        HUD.hudOrderRaw = stored
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), orderedDummies())
    }

    /**
     * The overlay follows the user's arrangement, and the raw string behind it is not a setting.
     *
     * There used to be an "OverlayOrder: Width / Published" switch, which asked the user to make the
     * same choice twice; an untouched list now stays the reference's width wedge and an arranged one
     * is drawn as arranged. The "WTap,STap,…" string the ▲▼ buttons maintain is bookkeeping and must
     * not appear as a text field in the option list.
     */
    @Test
    fun `arranging the list turns on the published-order flag`() {
        assertFalse(HUD.overlayHeader().orderPublished, "nothing arranged yet")

        assertTrue(HUD.moveInHud("Charlie", "up"))
        assertTrue(
            HUD.overlayHeader().orderPublished,
            "the header must follow the arrangement immediately, not after the next unrelated change"
        )
    }

    @Test
    fun `the order bookkeeping and the removed switch are not offered as options`() {
        HUD.hudOrderRaw // touching the delegate registers the option
        val offered = ConfigManager.optionsOf(HUD.name).map { it.name }
        assertFalse(offered.contains("HudOrder"), "the raw order string is not a tunable: $offered")
        assertFalse(offered.contains("OverlayOrder"), "the Width/Published switch is gone: $offered")
    }
}
