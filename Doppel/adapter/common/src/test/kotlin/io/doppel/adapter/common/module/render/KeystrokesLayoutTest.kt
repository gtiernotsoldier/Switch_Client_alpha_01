package io.doppel.adapter.common.module.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins [KeystrokesLayout] against the numbers measured in **Lunar Keystrokes Mod v3-3.0**
 * (`com.rakib.keystrokes`), read from the jar with `javap -p -c -constants`.
 *
 * Every expected value in this file is a literal transcribed from the reference class files
 * (`Mode.class`, `KeystrokesHud.class`, `Config.class`) — not from this implementation — so a
 * regression in the port fails a test rather than silently drifting from the reference.
 */
class KeystrokesLayoutTest {

    private val wasdMode = KeystrokeMode.WASD
    private val mouseMode = KeystrokeMode.WASD_MOUSE
    private val jumpMode = KeystrokeMode.WASD_JUMP_MOUSE

    private fun options(mode: KeystrokeMode, scale: Float = 1.0f) =
        KeystrokesLayout.Options(mode = mode, scale = scale)

    // ═══════════════════════════════════════════
    //  Reference constants (javap literals)
    // ═══════════════════════════════════════════

    @Test
    fun `background colours are the exact reference literals`() {
        // KeystrokesHud.onRender: ldc -872415232 : ldc 1426063360
        assertEquals(-872415232, KeystrokesLayout.BACKGROUND_PRESSED)
        assertEquals(1426063360, KeystrokesLayout.BACKGROUND_RELEASED)
        assertEquals(0xCC000000.toInt(), KeystrokesLayout.BACKGROUND_PRESSED)
        assertEquals(0x55000000.toInt(), KeystrokesLayout.BACKGROUND_RELEASED)
    }

    @Test
    fun `config colour defaults are the exact reference literals`() {
        // Config.<clinit>: keystrokesDefaultColor = -1, keystrokesPressedColor = -5592406
        assertEquals(-1, KeystrokesLayout.TEXT_DEFAULT_ARGB)
        assertEquals(-5592406, KeystrokesLayout.TEXT_PRESSED_ARGB)
        assertEquals(0xFFFFFFFF.toInt(), KeystrokesLayout.TEXT_DEFAULT_ARGB)
        assertEquals(0xFFAAAAAA.toInt(), KeystrokesLayout.TEXT_PRESSED_ARGB)
    }

    @Test
    fun `config position and scale defaults are the exact reference literals`() {
        // Config.<clinit>: keystrokesX = 4, keystrokesY = 4, keystrokesScale = 1.0f
        assertEquals(4, KeystrokesLayout.DEFAULT_X)
        assertEquals(4, KeystrokesLayout.DEFAULT_Y)
        assertEquals(1.0f, KeystrokesLayout.DEFAULT_SCALE)
        // SettingsGui clamps the HUD scale to 0.5f..3.0f (cfg: "0.5 to 3.0")
        assertEquals(0.5f, KeystrokesLayout.MIN_SCALE)
        assertEquals(3.0f, KeystrokesLayout.MAX_SCALE)
    }

    // ═══════════════════════════════════════════
    //  Box geometry (Mode.createWASD / …WithMouse / …WithJumpAndMouse)
    // ═══════════════════════════════════════════

    @Test
    fun `WASD boxes match the reference coordinates and 18x18 size`() {
        val l = KeystrokesLayout.compute(options = options(wasdMode))
        assertEquals(listOf("W", "A", "S", "D"), l.boxes.map { it.id })

        assertBox(l, "W", 21, 1, 18, 18)
        assertBox(l, "A", 1, 21, 18, 18)
        assertBox(l, "S", 21, 21, 18, 18)
        assertBox(l, "D", 41, 21, 18, 18)
    }

    @Test
    fun `box order and placement are identical to Mode's key list`() {
        // Mode's keys list is WASD then LMB/RMB then Jump; the render loop draws in list order.
        assertEquals(
            listOf("W", "A", "S", "D", "LMB", "RMB"),
            KeystrokesLayout.compute(options = options(mouseMode)).boxes.map { it.id }
        )
        assertEquals(
            listOf("W", "A", "S", "D", "LMB", "RMB", "Jump"),
            KeystrokesLayout.compute(options = options(jumpMode)).boxes.map { it.id }
        )
    }

    @Test
    fun `mouse buttons are the reference 28x18 boxes on the row below WASD`() {
        val l = KeystrokesLayout.compute(options = options(mouseMode))
        assertBox(l, "LMB", 1, 41, 28, 18)
        assertBox(l, "RMB", 31, 41, 28, 18)
        // 1 (pad) + 28 + 2 (gap) + 28 = 59 → the row is flush with the D column edge.
        assertEquals(59, KeystrokesLayout.RMB_X + KeystrokesLayout.MOUSE_WIDTH)
        assertEquals(59, KeystrokesLayout.D_X + KeystrokesLayout.KEY_SIZE)
    }

    @Test
    fun `jump bar is the reference 58x12 box spanning the whole mouse row`() {
        val l = KeystrokesLayout.compute(options = options(jumpMode))
        assertBox(l, "Jump", 1, 61, 58, 12)
        // 58 = LMB + gap + RMB, i.e. the mouse row width, and it starts at the same x.
        assertEquals(KeystrokesLayout.JUMP_WIDTH, KeystrokesLayout.MOUSE_WIDTH * 2 + KeystrokesLayout.GAP)
        assertEquals(KeystrokesLayout.LMB_X, KeystrokesLayout.JUMP_X)
        assertEquals("Jump", l.box("Jump")!!.label)
    }

    @Test
    fun `jump bar is only drawn in mode 2`() {
        assertFalse(KeystrokesLayout.compute(options = options(wasdMode)).boxes.any { it.id == "Jump" })
        assertFalse(KeystrokesLayout.compute(options = options(mouseMode)).boxes.any { it.id == "Jump" })
        assertTrue(KeystrokesLayout.compute(options = options(jumpMode)).boxes.any { it.id == "Jump" })
    }

    // ═══════════════════════════════════════════
    //  Widget size (Mode.<init>: max + 4)
    // ═══════════════════════════════════════════

    @Test
    fun `widget size is maxExtent + 4 for every mode`() {
        assertEquals(63 to 43, sizeOf(wasdMode))
        assertEquals(63 to 63, sizeOf(mouseMode))
        assertEquals(63 to 77, sizeOf(jumpMode))
        // 63 = D right edge 59 + 4; 77 = Jump bottom edge 73 + 4.
        assertEquals(KeystrokesLayout.D_X + KeystrokesLayout.KEY_SIZE + KeystrokesLayout.BOUNDS_INSET, 63)
        assertEquals(KeystrokesLayout.JUMP_Y + KeystrokesLayout.JUMP_HEIGHT + KeystrokesLayout.BOUNDS_INSET, 77)
    }

    @Test
    fun `left and top padding is 1px, right and bottom slack is the extra 3px of the inset`() {
        for (mode in KeystrokeMode.values()) {
            val l = KeystrokesLayout.compute(options = options(mode))
            val minX = l.boxes.minOf { it.localX }
            val minY = l.boxes.minOf { it.localY }
            val maxX = l.boxes.maxOf { it.localX + it.localWidth }
            val maxY = l.boxes.maxOf { it.localY + it.localHeight }
            assertEquals(KeystrokesLayout.PADDING, minX, "$mode left pad")
            assertEquals(KeystrokesLayout.PADDING, minY, "$mode top pad")
            // Mode.getWidth() = maxX + 4 → trailing slack is 4 - padding = 3.
            assertEquals(maxX + KeystrokesLayout.BOUNDS_INSET, l.width, "$mode width")
            assertEquals(maxY + KeystrokesLayout.BOUNDS_INSET, l.height, "$mode height")
        }
    }

    // ═══════════════════════════════════════════
    //  Gaps / the WASD "T"
    // ═══════════════════════════════════════════

    @Test
    fun `neighbouring boxes are separated by exactly the 2px gap`() {
        val l = KeystrokesLayout.compute(options = options(jumpMode))
        val w = l.box("W")!!
        val a = l.box("A")!!
        val s = l.box("S")!!
        val d = l.box("D")!!
        val lmb = l.box("LMB")!!
        val jump = l.box("Jump")!!

        // Movement keys form a T: W above S, A left of S, D right of S.
        assertEquals(s.localX, w.localX, "W sits above S")
        assertEquals(a.localY, s.localY, "A/S/D share a row")
        assertEquals(s.localX - (a.localX + a.localWidth), KeystrokesLayout.GAP, "A→S gap")
        assertEquals(d.localX - (s.localX + s.localWidth), KeystrokesLayout.GAP, "S→D gap")
        assertEquals(a.localY - (w.localY + w.localHeight), KeystrokesLayout.GAP, "W→A/S/D gap")
        assertEquals(lmb.localY - (a.localY + a.localHeight), KeystrokesLayout.GAP, "WASD→mouse gap")
        assertEquals(jump.localY - (lmb.localY + lmb.localHeight), KeystrokesLayout.GAP, "mouse→jump gap")
        // 2 = the difference between the reference's 1/21/41/61 spacing and an 18-high box.
        assertEquals(2, 21 - (1 + KeystrokesLayout.KEY_SIZE))
    }

    // ═══════════════════════════════════════════
    //  Text anchors (KeystrokesHud.onRender)
    // ═══════════════════════════════════════════

    @Test
    fun `text is centred horizontally and sits at y + height-2 - 4`() {
        val l = KeystrokesLayout.compute(options = options(jumpMode))

        val w = l.box("W")!!
        assertEquals(21 + 18 / 2, w.textCenterX)   // 30
        assertEquals(1 + 18 / 2 - 4, w.textY)      // 6
        // Reference: x + width/2 - stringWidth/2 → "W" is 6px wide in the vanilla font.
        assertEquals(21 + 9 - 3, w.textX(6))

        val lmb = l.box("LMB")!!
        assertEquals(1 + 28 / 2, lmb.textCenterX)  // 15
        assertEquals(41 + 18 / 2 - 4, lmb.textY)   // 46
        assertEquals(1 + 14 - 11, lmb.textX(22))   // 22px wide "LMB" → left edge 4

        val jump = l.box("Jump")!!
        assertEquals(1 + 58 / 2, jump.textCenterX) // 30
        assertEquals(61 + 12 / 2 - 4, jump.textY)  // 63
    }

    @Test
    fun `text anchor offset is the reference 4px`() {
        assertEquals(4, KeystrokesLayout.TEXT_BASELINE_INSET)
    }

    // ═══════════════════════════════════════════
    //  Labels (CPS substitution)
    // ═══════════════════════════════════════════

    @Test
    fun `zero CPS keeps the literal labels`() {
        val l = KeystrokesLayout.compute(options = options(mouseMode), leftCps = 0, rightCps = 0)
        assertEquals("LMB", l.box("LMB")!!.label)
        assertEquals("RMB", l.box("RMB")!!.label)
    }

    @Test
    fun `non-zero CPS replaces the mouse label with the bare number`() {
        val l = KeystrokesLayout.compute(options = options(mouseMode), leftCps = 7, rightCps = 12)
        // Reference: String.valueOf(getCPS(...)) — no "CPS" suffix, no separate block.
        assertEquals("7", l.box("LMB")!!.label)
        assertEquals("12", l.box("RMB")!!.label)
    }

    @Test
    fun `CPS of one button does not affect the other`() {
        val l = KeystrokesLayout.compute(options = options(mouseMode), leftCps = 0, rightCps = 4)
        assertEquals("LMB", l.box("LMB")!!.label)
        assertEquals("4", l.box("RMB")!!.label)
    }

    @Test
    fun `ShowCPS off always keeps the literal labels`() {
        val opts = KeystrokesLayout.Options(mode = mouseMode, showCps = false)
        val l = KeystrokesLayout.compute(options = opts, leftCps = 9, rightCps = 9)
        assertEquals("LMB", l.box("LMB")!!.label)
        assertEquals("RMB", l.box("RMB")!!.label)
    }

    // ═══════════════════════════════════════════
    //  Pressed vs released colours
    // ═══════════════════════════════════════════

    @Test
    fun `pressed boxes use the pressed background and pressed text colour`() {
        val states = KeystrokesLayout.KeyStates(forward = true, mouseLeft = true, jump = true)
        val l = KeystrokesLayout.compute(keyStates = states, options = options(jumpMode))

        val w = l.box("W")!!
        assertTrue(w.pressed)
        assertEquals(0xCC000000.toInt(), w.background)
        assertEquals(0xFFAAAAAA.toInt(), w.textColor)

        val a = l.box("A")!!
        assertFalse(a.pressed)
        assertEquals(0x55000000.toInt(), a.background)
        assertEquals(0xFFFFFFFF.toInt(), a.textColor)

        assertTrue(l.box("LMB")!!.pressed)
        assertTrue(l.box("Jump")!!.pressed)
        assertFalse(l.box("RMB")!!.pressed)
    }

    @Test
    fun `each key is wired to its own reference binding`() {
        val all = KeystrokesLayout.KeyStates(
            forward = true, back = true, left = true, right = true,
            jump = true, mouseLeft = true, mouseRight = true
        )
        assertTrue(KeystrokesLayout.compute(keyStates = all, options = options(jumpMode)).boxes.all { it.pressed })

        // W=forward, A=left, S=back, D=right, LMB=attack, RMB=use, Jump=jump — no cross-wiring.
        val onlyLeft = KeystrokesLayout.KeyStates(left = true)
        val l = KeystrokesLayout.compute(keyStates = onlyLeft, options = options(jumpMode))
        assertTrue(l.box("A")!!.pressed)
        assertTrue(l.boxes.filter { it.id != "A" }.none { it.pressed })
    }

    @Test
    fun `option colours override the reference defaults`() {
        val opts = KeystrokesLayout.Options(
            mode = mouseMode, defaultColor = 0x112233, pressedColor = 0x445566
        )
        val l = KeystrokesLayout.compute(keyStates = KeystrokesLayout.KeyStates(forward = true), options = opts)
        assertEquals(0xFF445566.toInt(), l.box("W")!!.textColor)
        assertEquals(0xFF112233.toInt(), l.box("A")!!.textColor)
    }

    // ═══════════════════════════════════════════
    //  Origin / scale / clamping
    // ═══════════════════════════════════════════

    @Test
    fun `origin defaults to the reference keystrokesX keystrokesY`() {
        val l = KeystrokesLayout.compute(options = options(mouseMode))
        assertEquals(4, l.originX)
        assertEquals(4, l.originY)
        // Screen rects are the Lunar local rects shifted by the origin.
        assertEquals(4 + 21, l.box("W")!!.screenX)
        assertEquals(4 + 1, l.box("W")!!.screenY)
        assertEquals(4 + 1, l.box("LMB")!!.screenX)
        assertEquals(4 + 41, l.box("LMB")!!.screenY)
    }

    @Test
    fun `scale multiplies the widget footprint and every screen rect`() {
        val l = KeystrokesLayout.compute(options = options(jumpMode, scale = 2f), originX = 10, originY = 20)
        assertEquals(126, l.screenWidth)   // 63 * 2
        assertEquals(154, l.screenHeight)  // 77 * 2
        assertEquals(10 + 42, l.box("W")!!.screenX)
        assertEquals(20 + 2, l.box("W")!!.screenY)
        assertEquals(56, l.box("LMB")!!.screenWidth)   // 28 * 2
        assertEquals(36, l.box("LMB")!!.screenHeight)  // 18 * 2
        // Local geometry (what the GL matrix scales) never changes with the scale option.
        assertEquals(KeystrokesLayout.W, KeySpec("W", l.box("W")!!.localX, l.box("W")!!.localY, 18, 18))
    }

    @Test
    fun `origin is clamped into the screen with the scaled footprint`() {
        val l = KeystrokesLayout.compute(
            options = options(jumpMode), originX = 500, originY = 500,
            screenWidth = 100, screenHeight = 100
        )
        assertEquals(100 - 63, l.originX)  // 37
        assertEquals(100 - 77, l.originY)  // 23
        // Negative origins clamp to 0 too.
        val neg = KeystrokesLayout.compute(
            options = options(jumpMode), originX = -30, originY = -30,
            screenWidth = 100, screenHeight = 100
        )
        assertEquals(0, neg.originX)
        assertEquals(0, neg.originY)
    }

    @Test
    fun `no screen dimensions means no clamping`() {
        val l = KeystrokesLayout.compute(options = options(jumpMode), originX = 500, originY = 500)
        assertEquals(500, l.originX)
        assertEquals(500, l.originY)
    }

    @Test
    fun `screen rects follow the clamped origin`() {
        val l = KeystrokesLayout.compute(
            options = options(mouseMode), originX = 500, originY = 500,
            screenWidth = 100, screenHeight = 100
        )
        assertEquals(l.originX + 31, l.box("RMB")!!.screenX)
        assertEquals(l.originY + 41, l.box("RMB")!!.screenY)
    }

    // ═══════════════════════════════════════════
    //  Mode plumbing
    // ═══════════════════════════════════════════

    @Test
    fun `mode ids and labels match the reference enum`() {
        // Mode.values(): 0 WASD, 1 WASD_MOUSE, 2 WASD_JUMP_MOUSE (Mode.name() is shown in the GUI)
        assertEquals(listOf(0, 1, 2), KeystrokeMode.values().map { it.modeId })
        assertEquals(listOf("WASD", "WASD_MOUSE", "WASD_JUMP_MOUSE"), KeystrokeMode.values().map { it.name })
        assertEquals(listOf("WASD", "WASD+Mouse", "WASD+Jump+Mouse"), KeystrokeMode.LABELS.toList())
        assertEquals(KeystrokeMode.WASD, KeystrokeMode.forLabel("WASD"))
        assertEquals(KeystrokeMode.WASD_MOUSE, KeystrokeMode.forLabel("WASD+Mouse"))
        assertEquals(KeystrokeMode.WASD_JUMP_MOUSE, KeystrokeMode.forLabel("WASD+Jump+Mouse"))
        // Unknown label falls back to the first mode (the option default).
        assertEquals(KeystrokeMode.WASD, KeystrokeMode.forLabel("nonsense"))
    }

    // ── helpers ──

    private fun sizeOf(mode: KeystrokeMode): Pair<Int, Int> {
        val l = KeystrokesLayout.compute(options = options(mode))
        return l.width to l.height
    }

    private fun assertBox(
        layout: KeystrokesLayout.Layout,
        id: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        val box = layout.box(id) ?: throw AssertionError("no box '$id' in ${layout.boxes.map { it.id }}")
        assertEquals(x, box.localX, "$id.x")
        assertEquals(y, box.localY, "$id.y")
        assertEquals(w, box.localWidth, "$id.width")
        assertEquals(h, box.localHeight, "$id.height")
    }
}
