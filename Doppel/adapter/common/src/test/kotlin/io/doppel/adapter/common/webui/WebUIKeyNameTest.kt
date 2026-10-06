package io.doppel.adapter.common.webui

import io.doppel.adapter.common.api.KeyCode
import io.doppel.adapter.common.api.KeyNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The WebUI keybind display is English now.
 *
 * The panel used to print `未绑定` / `空格` / `键A` while Minecraft's own controls screen names the
 * same keys `Space` / `A` / `LSHIFT`. Two vocabularies for one keyboard is how a user binds a module
 * to a key the game already uses without noticing, so the labels now follow the game's own spelling
 * and nothing here may contain a non-ASCII character.
 */
class WebUIKeyNameTest {

    @Test
    fun `special keys use the game's own names`() {
        assertEquals("NONE", WebUIServer.keyName(-1))
        assertEquals("NONE", WebUIServer.keyName(0))
        assertEquals(KeyNames.UNBOUND, WebUIServer.keyName(-1), "the panel's chip shows [NONE]")
        assertEquals("Space", WebUIServer.keyName(KeyCode.SPACE))
        assertEquals("Enter", WebUIServer.keyName(KeyCode.ENTER))
        assertEquals("Tab", WebUIServer.keyName(KeyCode.TAB))
        assertEquals("Backspace", WebUIServer.keyName(KeyCode.BACKSPACE))
        assertEquals("Esc", WebUIServer.keyName(KeyCode.ESC))
        assertEquals("LShift", WebUIServer.keyName(KeyCode.LEFT_SHIFT))
        assertEquals("RShift", WebUIServer.keyName(KeyCode.RIGHT_SHIFT))
        assertEquals("LCtrl", WebUIServer.keyName(KeyCode.LEFT_CONTROL))
        assertEquals("RCtrl", WebUIServer.keyName(KeyCode.RIGHT_CONTROL))
        assertEquals("LAlt", WebUIServer.keyName(KeyCode.LEFT_ALT))
        assertEquals("RAlt", WebUIServer.keyName(KeyCode.RIGHT_ALT))
    }

    @Test
    fun `letters, digits and function keys read like the keyboard`() {
        assertEquals("A", WebUIServer.keyName(KeyCode.KEY_A))
        assertEquals("W", WebUIServer.keyName(KeyCode.KEY_W))
        assertEquals("Z", WebUIServer.keyName(KeyCode.KEY_Z))
        assertEquals("0", WebUIServer.keyName(KeyCode.KEY_0))
        assertEquals("9", WebUIServer.keyName(KeyCode.KEY_9))
        assertEquals("F1", WebUIServer.keyName(KeyCode.F1))
        assertEquals("F12", WebUIServer.keyName(KeyCode.F12))
        assertEquals("Up", WebUIServer.keyName(265))
        assertEquals("Num 5", WebUIServer.keyName(325))
    }

    @Test
    fun `every label is plain ASCII`() {
        // The whole point of the change: no Chinese next to the game's English key names.
        val samples = (-1..400).map { WebUIServer.keyName(it) }
        for (label in samples) {
            assertTrue(
                label.all { it.code in 32..126 },
                "key name {${label}} must be ASCII so it cannot be confused with MC's own labels"
            )
        }
    }
}
