package io.doppel.adapter.common.api

/**
 * GLFW key code → the key's English name.
 *
 * One naming table for everything that shows a key: the WebUI's keybind chip and the HUD list's
 * `[KEY]` suffix both go through here, so a key cannot be called `Space` in one place and `空格` in
 * another. The names follow Minecraft's own controls screen (`LShift`, `Space`, `F1`) because the
 * user compares the two side by side — two vocabularies for one keyboard is how a module ends up
 * bound to a key the game already uses without anyone noticing.
 *
 * Lives in `api` rather than in the WebUI so the module layer can use it without depending on the
 * HTTP server.
 */
object KeyNames {

    /** What an unbound option shows. Deliberately short: it sits in a `[...]` chip. */
    const val UNBOUND: String = "NONE"

    fun name(code: Int): String {
        if (code <= 0) return UNBOUND
        return when (code) {
            KeyCode.ESC -> "Esc"
            KeyCode.ENTER -> "Enter"
            KeyCode.TAB -> "Tab"
            KeyCode.BACKSPACE -> "Backspace"
            KeyCode.SPACE -> "Space"
            KeyCode.LEFT_SHIFT -> "LShift"
            KeyCode.RIGHT_SHIFT -> "RShift"
            KeyCode.LEFT_CONTROL -> "LCtrl"
            KeyCode.RIGHT_CONTROL -> "RCtrl"
            KeyCode.LEFT_ALT -> "LAlt"
            KeyCode.RIGHT_ALT -> "RAlt"
            in 48..57 -> "" + (code - 48)
            in 65..90 -> "" + ((code - 65) + 'A'.code).toChar()
            in 290..301 -> "F${code - 290 + 1}"
            // GLFW's arrow keys, which a module keybind may well use.
            265 -> "Up"
            264 -> "Down"
            263 -> "Left"
            262 -> "Right"
            // GLFW's numpad block.
            in 320..329 -> "Num ${code - 320}"
            else -> "Key $code"
        }
    }
}
