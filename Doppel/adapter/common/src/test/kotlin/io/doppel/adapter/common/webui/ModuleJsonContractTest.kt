package io.doppel.adapter.common.webui

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the panel is actually told about a module.
 *
 * "The web page does not show the bound key" can only be answered from here: either the payload
 * carries `keybind`/`keyName` and the failure is in the panel, or it does not and the failure is
 * here. Pinning the fields makes that a fact rather than an opinion.
 */
class ModuleJsonContractTest {

    private class Bound : Module("JsonBound", Category.RENDER)

    private fun module(): Bound {
        val m = Bound()
        if (ModuleRegistry.get(m.name) == null) ModuleRegistry.register(m)
        return m
    }

    @Test
    fun `the payload carries the binding, its display name and the HUD flags`() {
        val m = module()
        m.keybind = 78 // GLFW 'N'
        m.hudHidden = true
        m.showRedIndicator = false

        val json = WebUIServer.moduleJson(m)

        assertEquals(78, json["keybind"], "the raw code, for the capture flow")
        assertEquals("N", json["keyName"], "the English label the chip renders")
        assertEquals(true, json["hudHidden"])
        assertEquals(false, json["hudShown"], "hudShown is the inverse the panel reads")
        assertEquals(false, json["redIndicator"], "the red work-flash state")
        assertTrue(json.containsKey("hudIndex"), "the row order")
        assertTrue(json.containsKey("hudValue"), "the module's own published HUD string")
        assertTrue(json.containsKey("options"), "the tunable options")
    }

    @Test
    fun `an unbound module is reported as NONE`() {
        val m = module()
        m.keybind = Module.NO_KEYBIND
        assertEquals("NONE", WebUIServer.moduleJson(m)["keyName"])
        assertEquals(-1, WebUIServer.moduleJson(m)["keybind"])
    }

    @Test
    fun `a rebind is visible in the very next payload`() {
        val m = module()
        m.keybind = 78
        assertEquals("N", WebUIServer.moduleJson(m)["keyName"])

        m.keybind = 290 // F1
        assertEquals("F1", WebUIServer.moduleJson(m)["keyName"], "no caching between the two")
    }

    @Test
    fun `the persisted bookkeeping options are not offered to the panel`() {
        val m = module()
        @Suppress("UNCHECKED_CAST")
        val options = WebUIServer.moduleJson(m)["options"] as List<Map<String, Any?>>
        val names = options.map { it["name"] }
        assertTrue(!names.contains("Keybind"), "a Keybind slider is not a setting: $names")
        assertTrue(!names.contains("RedIndicator"))
    }
}
