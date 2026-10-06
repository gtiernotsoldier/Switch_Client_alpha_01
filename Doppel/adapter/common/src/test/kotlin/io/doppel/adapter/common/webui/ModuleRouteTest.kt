package io.doppel.adapter.common.webui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The per-module API route parser.
 *
 * This exists because of a real bug: the module name used to be derived by stripping the known
 * suffixes (`.removeSuffix("/toggle")`), so `/api/modules/Velocity/hud` looked up a module called
 * `"Velocity/keybind"`, found nothing, and answered 200 without doing anything. Every per-module HUD
 * control and every keybind was silently dead in the panel, and the panel's "authoritative" response
 * then put the old value back on screen.
 */
class ModuleRouteTest {

    @Test
    fun `the list route has no module and no action`() {
        assertEquals("" to "", WebUIServer.moduleRoute("/api/modules"))
        assertEquals("" to "", WebUIServer.moduleRoute("/api/modules/"))
    }

    @Test
    fun `a bare module name is a module with no action`() {
        assertEquals("Velocity" to "", WebUIServer.moduleRoute("/api/modules/Velocity"))
    }

    @Test
    fun `every action resolves to the module, not to a path`() {
        assertEquals("Velocity" to "toggle", WebUIServer.moduleRoute("/api/modules/Velocity/toggle"))
        assertEquals("Velocity" to "keybind", WebUIServer.moduleRoute("/api/modules/Velocity/keybind"))
        assertEquals("Velocity" to "hud", WebUIServer.moduleRoute("/api/modules/Velocity/hud"))
        assertEquals("Velocity" to "options", WebUIServer.moduleRoute("/api/modules/Velocity/options"))
    }

    @Test
    fun `a name that needs decoding is decoded`() {
        assertEquals("Auto Block" to "hud", WebUIServer.moduleRoute("/api/modules/Auto%20Block/hud"))
        assertEquals("A+B" to "toggle", WebUIServer.moduleRoute("/api/modules/A%2BB/toggle"))
    }

    @Test
    fun `other API paths are not module routes`() {
        assertNull(WebUIServer.moduleRoute("/api/info"))
        assertNull(WebUIServer.moduleRoute("/api/options"))
        assertNull(WebUIServer.moduleRoute("/api/modulesX"))
    }

    @Test
    fun `a trailing segment beyond the action is ignored`() {
        assertEquals("Velocity" to "hud", WebUIServer.moduleRoute("/api/modules/Velocity/hud/extra"))
    }
}
