package io.doppel.adapter.common.module.render

/**
 * Keystrokes layout maths — a pure, GL-free port of the layout used by
 * **Lunar Keystrokes Mod v3-3.0** (`com.rakib.keystrokes`, `[1.8.9] Lunar Keystrokes Mod v3-3.0.jar`).
 *
 * Given key states, CPS values, option values and screen dimensions this object returns the
 * rectangles and labels to draw. No GL, no Minecraft classes, no clock — the geometry is
 * unit-testable without a running game ([KeystrokesLayoutTest] pins every number below against
 * the reference class files).
 *
 * ## Evidence (jar entries read with `javap -p -c -constants`)
 *
 * `com/rakib/keystrokes/render/Mode.class`
 * ```
 * private static List<Key> createWASD();
 *   new Key("W",  keyBindForward, 21,  1, 18, 18)
 *   new Key("A",  keyBindLeft,     1, 21, 18, 18)
 *   new Key("S",  keyBindBack,    21, 21, 18, 18)
 *   new Key("D",  keyBindRight,   41, 21, 18, 18)
 * private static List<Key> createWASDWithMouse();     // + createWASD()
 *   new Key("LMB", keyBindAttack,  1, 41, 28, 18)
 *   new Key("RMB", keyBindUseItem,31, 41, 28, 18)
 * private static List<Key> createWASDWithJumpAndMouse();  // + createWASDWithMouse()
 *   new Key("Jump", keyBindJump,   1, 61, 58, 12)
 * // widget size:
 * width  = max(x + w over all keys) + 4
 * height = max(y + h over all keys) + 4
 * ```
 * The constructor is `Key(String name, KeyBinding bind, int x, int y, int width, int height)`.
 *
 * `com/rakib/keystrokes/hud/KeystrokesHud.class` (the render loop)
 * ```
 * bg   = down ? -872415232 : 1426063360          // 0xCC000000 : 0x55000000
 * text = down ? Config.keystrokesPressedColor : Config.keystrokesDefaultColor
 * name = ("LMB".equals(name) && getCPS(left)  != 0) ? String.valueOf(getCPS(left))  : name
 * name = ("RMB".equals(name) && getCPS(right) != 0) ? String.valueOf(getCPS(right)) : name
 * Gui.drawRect(x, y, x + w, y + h, bg)
 * fr.drawStringWithShadow(name, x + w / 2 - fr.getStringWidth(name) / 2, y + h / 2 - 4, text)
 * glTranslatef(keystrokesX, keystrokesY, 0); glScalef(keystrokesScale, keystrokesScale, 1)
 * ```
 * Note the CPS counter *replaces* the LMB/RMB label — it has no " CPS" suffix and no separate
 * block. There is no outline, no rounded corner and no drop shadow on the boxes: a key is a flat
 * rectangle plus centred shadowed text.
 *
 * `com/rakib/keystrokes/Config.class` (shipped defaults = `Config.<clinit>`)
 * ```
 * keystrokesX = 4            keystrokesY = 4
 * keystrokesEnabled = true   keystrokesMode = 0        (WASD)
 * keystrokesScale = 1.0f     keystrokesDefaultColor = -1        (0xFFFFFFFF)
 * keystrokesPressedColor = -5592406                             (0xFFAAAAAA)
 * ```
 * `SettingsGui` clamps the scale to `0.5f..3.0f`, matching the cfg comment
 * `# keystrokesScale & fpsScale: HUD scaling factor (0.5 to 3.0)`.
 *
 * The `config/keystrokes.json` in the same folder is **not** this mod's config (it belongs to the
 * separate `dev.salmon.keystrokes` / OneConfig mod); the lunar mod only writes
 * `config/lunar-keystrokes.cfg`.
 */
object KeystrokesLayout {

    // ═══════════════════ Box metrics (Lunar, unscaled HUD units) ═══════════════════

    /** Movement key box: `new Key(name, bind, x, y, 18, 18)`. */
    const val KEY_SIZE = 18

    /** Mouse button box width: `new Key("LMB", bind, 1, 41, 28, 18)`. */
    const val MOUSE_WIDTH = 28

    /** Jump bar: `new Key("Jump", keyBindJump, 1, 61, 58, 12)` — the full mouse row, 12 high. */
    const val JUMP_WIDTH = 58
    const val JUMP_HEIGHT = 12

    /**
     * Gap between neighbouring boxes. Not a literal in the bytecode — derived from the
     * coordinates: `A(1..19)` → `W(21..39)` → `D(41..59)` horizontally and
     * `W(1..19)` → `A(21..39)` → `LMB(41..59)` → `Jump(61..73)` vertically, i.e. 2.
     */
    const val GAP = 2

    /** Padding of the first box from the widget origin: every left/top edge starts at 1. */
    const val PADDING = 1

    /** `Mode.<init>`: `width = max(x + w) + 4`, `height = max(y + h) + 4`. */
    const val BOUNDS_INSET = 4

    // ── Exact WASD block coordinates (Mode.createWASD) ──
    const val W_X = 21
    const val W_Y = 1
    const val A_X = 1
    const val A_Y = 21
    const val S_X = 21
    const val S_Y = 21
    const val D_X = 41
    const val D_Y = 21

    // ── Exact mouse row coordinates (Mode.createWASDWithMouse) ──
    const val LMB_X = 1
    const val LMB_Y = 41
    const val RMB_X = 31
    const val RMB_Y = 41

    // ── Exact jump bar coordinates (Mode.createWASDWithJumpAndMouse) ──
    const val JUMP_X = 1
    const val JUMP_Y = 61

    // ── Key ids (also the fallback labels) ──
    const val ID_FORWARD = "W"
    const val ID_LEFT = "A"
    const val ID_BACK = "S"
    const val ID_RIGHT = "D"
    const val ID_LMB = "LMB"
    const val ID_RMB = "RMB"
    const val ID_JUMP = "Jump"

    // ═══════════════════ Colours ═══════════════════

    /** Pressed key background — `KeystrokesHud` `ldc -872415232` = `0xCC000000`. */
    val BACKGROUND_PRESSED: Int = 0xCC000000.toInt()

    /** Released key background — `KeystrokesHud` `ldc 1426063360` = `0x55000000`. */
    val BACKGROUND_RELEASED: Int = 0x55000000.toInt()

    /** `Config.keystrokesDefaultColor` default `-1` = `0xFFFFFFFF` (opaque white). */
    val TEXT_DEFAULT_ARGB: Int = 0xFFFFFFFF.toInt()

    /** `Config.keystrokesPressedColor` default `-5592406` = `0xFFAAAAAA`. */
    val TEXT_PRESSED_ARGB: Int = 0xFFAAAAAA.toInt()

    /**
     * RGB halves of the two reference text colours. The HUD renderer composes the opaque alpha
     * (`0xFF000000 or rgb`), which is exactly the reference values above; the client's `color`
     * option delegate stores `0x00RRGGBB`, so the options carry these.
     */
    const val TEXT_DEFAULT_RGB: Int = 0xFFFFFF
    const val TEXT_PRESSED_RGB: Int = 0xAAAAAA

    /** Text anchor: `x + width/2 - textWidth/2`, `y + height/2 - 4`. */
    const val TEXT_BASELINE_INSET: Int = 4

    // ═══════════════════ Config defaults (Config.<clinit>) ═══════════════════

    const val DEFAULT_X = 4
    const val DEFAULT_Y = 4
    const val DEFAULT_SCALE = 1.0f

    /** `SettingsGui` clamps the HUD scale to 0.5f..3.0f (cfg comment: "0.5 to 3.0"). */
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3.0f

    // ═══════════════════ Fixed box placement ═══════════════════

    val W = KeySpec(ID_FORWARD, W_X, W_Y, KEY_SIZE, KEY_SIZE)
    val A = KeySpec(ID_LEFT, A_X, A_Y, KEY_SIZE, KEY_SIZE)
    val S = KeySpec(ID_BACK, S_X, S_Y, KEY_SIZE, KEY_SIZE)
    val D = KeySpec(ID_RIGHT, D_X, D_Y, KEY_SIZE, KEY_SIZE)
    val LMB = KeySpec(ID_LMB, LMB_X, LMB_Y, MOUSE_WIDTH, KEY_SIZE)
    val RMB = KeySpec(ID_RMB, RMB_X, RMB_Y, MOUSE_WIDTH, KEY_SIZE)
    val JUMP = KeySpec(ID_JUMP, JUMP_X, JUMP_Y, JUMP_WIDTH, JUMP_HEIGHT)

    // ═══════════════════ Inputs ═══════════════════

    /** Live key/button states (the client's `EventBridge` mirrors of the vanilla key bindings). */
    data class KeyStates(
        val forward: Boolean = false,
        val back: Boolean = false,
        val left: Boolean = false,
        val right: Boolean = false,
        val jump: Boolean = false,
        val mouseLeft: Boolean = false,
        val mouseRight: Boolean = false
    )

    /**
     * Option values, exactly the reference's configurable set.
     *
     * [defaultColor]/[pressedColor] are `0x00RRGGBB` (what the client's colour option stores);
     * the reference stores the same colour with an opaque alpha.
     */
    data class Options(
        val mode: KeystrokeMode = KeystrokeMode.WASD,
        val scale: Float = DEFAULT_SCALE,
        val defaultColor: Int = TEXT_DEFAULT_RGB,
        val pressedColor: Int = TEXT_PRESSED_RGB,
        /**
         * The reference has no toggle — the LMB/RMB label is *always* replaced by the live CPS
         * count. `true` reproduces that; `false` keeps the literal labels.
         */
        val showCps: Boolean = true
    )

    // ═══════════════════ Outputs ═══════════════════

    /**
     * One key box, both in widget-local Lunar units ([localX]/[localY]…, what the GL matrix is
     * scaled by) and in screen pixels ([screenX]/[screenY]…, for hit testing).
     */
    data class Box(
        val id: String,
        val label: String,
        val localX: Int,
        val localY: Int,
        val localWidth: Int,
        val localHeight: Int,
        val screenX: Int,
        val screenY: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        /** `x + width/2` — the text is centred on this. */
        val textCenterX: Int,
        /** `y + height/2 - 4`. */
        val textY: Int,
        val pressed: Boolean,
        val background: Int,
        val textColor: Int
    ) {
        /** Reference anchor: `x + width/2 - fontRenderer.getStringWidth(label)/2`. */
        fun textX(fontWidth: Int): Int = textCenterX - fontWidth / 2
    }

    /** One frame's layout: the boxes plus the widget footprint. */
    data class Layout(
        val boxes: List<Box>,
        /** Widget origin in screen pixels (clamped to the screen when dimensions are supplied). */
        val originX: Int,
        val originY: Int,
        val scale: Float,
        /** Widget size in Lunar units (`Mode.getWidth()` / `Mode.getHeight()`). */
        val width: Int,
        val height: Int,
        /** Widget footprint on screen: `(width * scale).toInt()`. */
        val screenWidth: Int,
        val screenHeight: Int
    ) {
        fun box(id: String): Box? = boxes.firstOrNull { it.id == id }
    }

    // ═══════════════════ The pure function ═══════════════════

    /**
     * Compute one frame of the keystrokes widget.
     *
     * @param keyStates current key/button states
     * @param leftCps right CPS counts over the last second (0 hides the count, as in the reference)
     * @param options option values
     * @param originX/param originY widget origin in screen pixels (the reference's keystrokesX/Y)
     * @param screenWidth/param screenHeight scaled screen size; pass 0 to skip clamping
     */
    fun compute(
        keyStates: KeyStates = KeyStates(),
        leftCps: Int = 0,
        rightCps: Int = 0,
        options: Options = Options(),
        originX: Int = DEFAULT_X,
        originY: Int = DEFAULT_Y,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ): Layout {
        val keys = options.mode.keys
        val scale = options.scale

        // Mode.<init>: width = max(x + w) + 4, height = max(y + h) + 4.
        val width = keys.maxOf { it.x + it.width } + BOUNDS_INSET
        val height = keys.maxOf { it.y + it.height } + BOUNDS_INSET
        val footprintW = (width * scale).toInt()
        val footprintH = (height * scale).toInt()

        // Clamp the origin into the screen exactly like the in-game drag does.
        var ox = originX
        var oy = originY
        if (screenWidth > 0 && screenHeight > 0) {
            ox = ox.coerceIn(0, (screenWidth - footprintW).coerceAtLeast(0))
            oy = oy.coerceIn(0, (screenHeight - footprintH).coerceAtLeast(0))
        }

        val boxes = keys.map { spec ->
            val pressed = isPressed(spec.id, keyStates)
            Box(
                id = spec.id,
                label = label(spec.id, leftCps, rightCps, options.showCps),
                localX = spec.x,
                localY = spec.y,
                localWidth = spec.width,
                localHeight = spec.height,
                // Scale is applied by the GL matrix; the integer rects below only feed hit tests.
                screenX = ox + (spec.x * scale).toInt(),
                screenY = oy + (spec.y * scale).toInt(),
                screenWidth = (spec.width * scale).toInt(),
                screenHeight = (spec.height * scale).toInt(),
                textCenterX = spec.x + spec.width / 2,
                textY = spec.y + spec.height / 2 - TEXT_BASELINE_INSET,
                pressed = pressed,
                background = if (pressed) BACKGROUND_PRESSED else BACKGROUND_RELEASED,
                textColor = opaque(if (pressed) options.pressedColor else options.defaultColor)
            )
        }

        return Layout(
            boxes = boxes,
            originX = ox,
            originY = oy,
            scale = scale,
            width = width,
            height = height,
            screenWidth = footprintW,
            screenHeight = footprintH
        )
    }

    /** The reference's `Key.isDown()` per box, mapped onto the client's key-state snapshot. */
    private fun isPressed(id: String, s: KeyStates): Boolean = when (id) {
        ID_FORWARD -> s.forward
        ID_LEFT -> s.left
        ID_BACK -> s.back
        ID_RIGHT -> s.right
        ID_LMB -> s.mouseLeft
        ID_RMB -> s.mouseRight
        ID_JUMP -> s.jump
        else -> false
    }

    /**
     * Reference label substitution: the LMB/RMB label becomes the raw CPS number whenever the
     * count is non-zero; every other box keeps its name.
     */
    private fun label(id: String, leftCps: Int, rightCps: Int, showCps: Boolean): String = when {
        showCps && id == ID_LMB && leftCps != 0 -> leftCps.toString()
        showCps && id == ID_RMB && rightCps != 0 -> rightCps.toString()
        else -> id
    }

    /** `0xFF000000 or (rgb and 0xFFFFFF)` — the reference passes the colour with alpha 0xFF. */
    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)
}

/** One key/button box's fixed placement in the widget, in unscaled Lunar HUD units. */
data class KeySpec(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * The reference's three modes (`com.rakib.keystrokes.render.Mode`), in the reference's `values()`
 * order — the same order the reference's settings GUI lists them in (`Mode:` + `Mode.name()`):
 *
 * - `0 WASD`            — `keystrokesMode=0`
 * - `1 WASD_MOUSE`      — `keystrokesMode=1`
 * - `2 WASD_JUMP_MOUSE` — `keystrokesMode=2`
 */
enum class KeystrokeMode(val label: String, val modeId: Int, val keys: List<KeySpec>) {
    WASD(
        "WASD", 0,
        listOf(KeystrokesLayout.W, KeystrokesLayout.A, KeystrokesLayout.S, KeystrokesLayout.D)
    ),
    WASD_MOUSE(
        "WASD+Mouse", 1,
        listOf(
            KeystrokesLayout.W, KeystrokesLayout.A, KeystrokesLayout.S, KeystrokesLayout.D,
            KeystrokesLayout.LMB, KeystrokesLayout.RMB
        )
    ),
    WASD_JUMP_MOUSE(
        "WASD+Jump+Mouse", 2,
        listOf(
            KeystrokesLayout.W, KeystrokesLayout.A, KeystrokesLayout.S, KeystrokesLayout.D,
            KeystrokesLayout.LMB, KeystrokesLayout.RMB, KeystrokesLayout.JUMP
        )
    );

    companion object {
        /** Display labels in `values()` order; the first entry is the option default. */
        val LABELS: Array<String> get() = values().map { it.label }.toTypedArray()

        fun forLabel(label: String): KeystrokeMode = values().firstOrNull { it.label == label } ?: WASD
    }
}
