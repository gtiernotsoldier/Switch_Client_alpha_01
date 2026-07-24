package io.switchlite.adapter.common.api

/**
 * Translates platform-specific key codes to the unified GLFW-based KeyCode standard.
 */
object KeyTranslator {

    /**
     * Translate LWJGL 2 (Forge 1.8.9) key code to GLFW standard.
     */
    fun fromLwjgl2(lwjglCode: Int): Int {
        return LWJGL2_TO_GLFW[lwjglCode] ?: lwjglCode
    }

    /**
     * LWJGL 2 Keyboard constants → GLFW key codes.
     * LWJGL 2 uses its own sequential codes, GLFW uses different values.
     * Reference: org.lwjgl.input.Keyboard (LWJGL 2) vs org.lwjgl.glfw.GLFW (LWJGL 3)
     */
    private val LWJGL2_TO_GLFW = mapOf(
        // Special keys
        1 to 256,    // KEY_ESCAPE → GLFW_ESCAPE
        28 to 257,   // KEY_RETURN → GLFW_ENTER
        15 to 258,   // KEY_TAB → GLFW_TAB
        14 to 259,   // KEY_BACK → GLFW_BACKSPACE
        57 to 32,    // KEY_SPACE → GLFW_SPACE

        // Modifier keys
        42 to 340,   // KEY_LSHIFT → GLFW_LEFT_SHIFT
        54 to 344,   // KEY_RSHIFT → GLFW_RIGHT_SHIFT
        29 to 341,   // KEY_LCONTROL → GLFW_LEFT_CONTROL
        157 to 345,  // KEY_RCONTROL → GLFW_RIGHT_CONTROL
        56 to 342,   // KEY_LMENU → GLFW_LEFT_ALT
        184 to 346,  // KEY_RMENU → GLFW_RIGHT_ALT

        // Number row (LWJGL 2: 2-11, GLFW: 49-57, 48)
        2 to 49,     // KEY_1
        3 to 50,     // KEY_2
        4 to 51,     // KEY_3
        5 to 52,     // KEY_4
        6 to 53,     // KEY_5
        7 to 54,     // KEY_6
        8 to 55,     // KEY_7
        9 to 56,     // KEY_8
        10 to 57,    // KEY_9
        11 to 48,    // KEY_0

        // Letter keys (LWJGL 2: 30-50, GLFW: 65-90)
        30 to 65,    // KEY_A
        48 to 66,    // KEY_B
        46 to 67,    // KEY_C
        32 to 68,    // KEY_D
        18 to 69,    // KEY_E
        33 to 70,    // KEY_F
        34 to 71,    // KEY_G
        35 to 72,    // KEY_H
        23 to 73,    // KEY_I
        36 to 74,    // KEY_J
        37 to 75,    // KEY_K
        38 to 76,    // KEY_L
        50 to 77,    // KEY_M
        49 to 78,    // KEY_N
        24 to 79,    // KEY_O
        25 to 80,    // KEY_P
        16 to 81,    // KEY_Q
        19 to 82,    // KEY_R
        31 to 83,    // KEY_S
        20 to 84,    // KEY_T
        22 to 85,    // KEY_U
        47 to 86,    // KEY_V
        17 to 87,    // KEY_W
        45 to 88,    // KEY_X
        21 to 89,    // KEY_Y
        44 to 90,    // KEY_Z

        // Function keys
        59 to 290,   // KEY_F1
        60 to 291,   // KEY_F2
        61 to 292,   // KEY_F3
        62 to 293,   // KEY_F4
        63 to 294,   // KEY_F5
        64 to 295,   // KEY_F6
        65 to 296,   // KEY_F7
        66 to 297,   // KEY_F8
        67 to 298,   // KEY_F9
        68 to 299,   // KEY_F10
        87 to 300,   // KEY_F11
        88 to 301,   // KEY_F12

        // Arrow keys
        200 to 265,  // KEY_UP
        208 to 264,  // KEY_DOWN
        203 to 263,  // KEY_LEFT
        205 to 262,  // KEY_RIGHT

        // Other
        210 to 260,  // KEY_INSERT → GLFW_INSERT
        211 to 261,  // KEY_DELETE → GLFW_DELETE
        199 to 268,  // KEY_HOME
        207 to 269,  // KEY_END
        201 to 266,  // KEY_PRIOR (PageUp)
        209 to 267,  // KEY_NEXT (PageDown)
    )
}
