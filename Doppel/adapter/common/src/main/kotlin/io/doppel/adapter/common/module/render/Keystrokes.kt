package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.render.RenderContext
import io.doppel.adapter.common.ui.RenderUtils

/**
 * Keystrokes — in-game key/button press indicator.
 *
 * **Layout: Lunar Keystrokes Mod v3-3.0** (the geometry was extracted from the mod jar and is
 * pinned by [KeystrokesLayout]'s tests):
 * ```
 *   WASD                 +Mouse                    +Jump
 *   ┌────┬────┬────┐     ┌────┬────┬────┐          ┌────┬────┬────┐
 *   │    │ W  │    │     │    │ W  │    │          │    │ W  │    │
 *   ├────┼────┼────┤     ├────┼────┼────┤          ├────┼────┼────┤
 *   │ A  │ S  │ D  │     │ A  │ S  │ D  │          │ A  │ S  │ D  │
 *   └────┴────┴────┘     ├──────┬──────┤          ├──────┬──────┤
 *                        │ LMB  │ RMB  │          │ LMB  │ RMB  │
 *                        └──────┴──────┘          ├─────────────┤
 *                                                 │    Jump     │
 *                                                 └─────────────┘
 * ```
 *
 * **Look: our own renderer** — the Raven-style per-key drawing this module used before the Lunar
 * port, kept because it reads better than the reference's flat boxes:
 * - a press flash: the background is `0x78000000 | g`, with `g` climbing 0..255 in ~128 ms on press
 *   and falling back on release;
 * - the label darkens on press (`factor = 1 - elapsed/20`) and is tinted by the theme colour;
 * - an optional 1 px outline in that colour;
 * - the mouse boxes print our own `"<n> CPS"` counter in white at half scale, fed by
 *   [EventBridge.leftCps]/[EventBridge.rightCps] — i.e. by the click modules, so the widget reacts
 *   to AutoClicker/ClickAssist like the rest of the HUD (the module-linkage behaviour);
 * - the jump key is the wide bar with the centred line (our extension) rather than a label.
 *
 * Key state also comes from [EventBridge], so module-driven presses (WTap and friends) light the
 * widget up exactly like physical ones.
 */
object Keystrokes : Module("Keystrokes", Category.RENDER) {

    // ── Theme palette (the pre-Lunar order: White, Red, Green, Blue, Yellow, Purple, Rainbow) ──
    private val THEME_COLORS = intArrayOf(
        0xFFFFFF, // White
        0xFF0000, // Red
        0x00FF00, // Green
        0x0000FF, // Blue
        0xFFFF00, // Yellow
        0xAA00AA, // Purple
        -1        // Rainbow (computed per frame)
    )

    @Volatile
    var posX: Int = KeystrokesLayout.DEFAULT_X
        private set
    @Volatile
    var posY: Int = KeystrokesLayout.DEFAULT_Y
        private set

    /** Unscaled Lunar rectangle set to draw: WASD / WASD+Mouse / WASD+Jump+Mouse. */
    var mode by choices("Mode", KeystrokeMode.LABELS)

    /** Widget scale factor (1.0 = 100 %). */
    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    /** Theme colour applied to labels, outlines and the press flash. */
    var colorIndex by choices("TextColor", arrayOf("White", "Red", "Green", "Blue", "Yellow", "Purple", "Rainbow"))

    /** Whether to draw the 1 px outline around each box. */
    var outline by boolean("Outline", false)

    /** Whether the mouse boxes print the live CPS counter under their label. */
    var showCps by boolean("ShowCPS", true)

    // ── Per-box animation state (wasDown + the instant it changed), as the old renderer kept it ──
    private class Anim {
        var down = false
        var changedAt = 0L
    }

    private val anims = HashMap<String, Anim>()
    private fun anim(label: String): Anim = anims.getOrPut(label) { Anim() }

    // ── Drag state ──
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    fun render(ctx: RenderContext) {
        if (!enabled) return
        val layout = handleDrag(ctx, snapshot(ctx))
        posX = layout.originX
        posY = layout.originY
        draw(ctx, layout)
    }

    // ═══════════════════════════════════════════
    //  Layout snapshot (pure — see KeystrokesLayout)
    // ═══════════════════════════════════════════

    private fun snapshot(ctx: RenderContext): KeystrokesLayout.Layout = KeystrokesLayout.compute(
        keyStates = KeystrokesLayout.KeyStates(
            forward = EventBridge.isKeyForwardDown,
            back = EventBridge.isKeyBackDown,
            left = EventBridge.isKeyLeftDown,
            right = EventBridge.isKeyRightDown,
            jump = EventBridge.isKeyJumpDown,
            mouseLeft = EventBridge.mouseButton0,
            mouseRight = EventBridge.mouseButton1
        ),
        leftCps = EventBridge.leftCps(),
        rightCps = EventBridge.rightCps(),
        options = KeystrokesLayout.Options(mode = KeystrokeMode.forLabel(mode), scale = scale),
        originX = posX,
        originY = posY,
        screenWidth = ctx.scaledWidth,
        screenHeight = ctx.scaledHeight
    )

    /** Which key a Lunar box id stands for — the same [EventBridge] source the layout uses. */
    private fun isDown(id: String): Boolean = when (id) {
        KeystrokesLayout.ID_FORWARD -> EventBridge.isKeyForwardDown
        KeystrokesLayout.ID_LEFT -> EventBridge.isKeyLeftDown
        KeystrokesLayout.ID_BACK -> EventBridge.isKeyBackDown
        KeystrokesLayout.ID_RIGHT -> EventBridge.isKeyRightDown
        KeystrokesLayout.ID_LMB -> EventBridge.mouseButton0
        KeystrokesLayout.ID_RMB -> EventBridge.mouseButton1
        KeystrokesLayout.ID_JUMP -> EventBridge.isKeyJumpDown
        else -> false
    }

    // ═══════════════════════════════════════════
    //  Drag (only while a GUI screen is open / paused)
    // ═══════════════════════════════════════════

    private fun handleDrag(ctx: RenderContext, start: KeystrokesLayout.Layout): KeystrokesLayout.Layout {
        if (!EventBridge.isGuiOpen || !EventBridge.guiLeftMouseDown) {
            dragging = false
            return start
        }
        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY

        if (!dragging) {
            if (mx >= start.originX && mx < start.originX + start.screenWidth &&
                my >= start.originY && my < start.originY + start.screenHeight
            ) {
                dragging = true
                dragOffsetX = mx - start.originX
                dragOffsetY = my - start.originY
            }
            return start
        }

        posX = mx - dragOffsetX
        posY = my - dragOffsetY
        return snapshot(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drawing
    // ═══════════════════════════════════════════

    private fun draw(ctx: RenderContext, layout: KeystrokesLayout.Layout) {
        val g = ctx.gl
        val font = ctx.fontRenderer
        val theme = themeColor()

        g.glPushMatrix()
        try {
            // The widget is translated to its origin and scaled, so every box is drawn in the
            // unscaled Lunar coordinates the layout produced.
            g.glTranslatef(layout.originX.toFloat(), layout.originY.toFloat(), 0f)
            g.glScalef(layout.scale, layout.scale, 1f)

            for (box in layout.boxes) {
                val down = isDown(box.label)
                val a = anim(box.label)
                if (down != a.down) {
                    a.down = down
                    a.changedAt = System.currentTimeMillis()
                }
                val elapsed = System.currentTimeMillis() - a.changedAt

                // Background flash: 0 -> 255 over ~128 ms while held, 255 -> 0 after release.
                val flash = if (down) {
                    kotlin.math.min(255, (2 * elapsed).toInt())
                } else {
                    kotlin.math.max(0, 255 - (2 * elapsed).toInt())
                }
                // Text factor: darkens on press (1 -> 0 in 20 ms), brightens again after release.
                val factor = if (down) {
                    kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
                } else {
                    kotlin.math.min(1.0, elapsed / 20.0)
                }

                val x = box.localX.toFloat()
                val y = box.localY.toFloat()
                val w = box.localWidth.toFloat()
                val h = box.localHeight.toFloat()

                RenderUtils.rect(ctx, x, y, w, h,
                    0x78000000.toInt() or (flash shl 16) or (flash shl 8) or flash)

                if (outline) {
                    val border = 0xFF000000.toInt() or theme
                    RenderUtils.rect(ctx, x, y, w, 1f, border)
                    RenderUtils.rect(ctx, x, y + h - 1f, w, 1f, border)
                    RenderUtils.rect(ctx, x, y, 1f, h, border)
                    RenderUtils.rect(ctx, x + w - 1f, y, 1f, h, border)
                }

                if (box.label == KeystrokesLayout.ID_JUMP) {
                    // The jump bar, our extension: a centred line instead of a label.
                    val lineW = (w * 0.7f).coerceAtLeast(6f)
                    val lineH = 2f.coerceAtLeast(1f)
                    val lineColor = textColor(theme, factor)
                    RenderUtils.rect(ctx, x + (w - lineW) / 2f, y + (h - lineH) / 2f, lineW, lineH, lineColor)
                    continue
                }

                val labelColor = textColor(theme, factor)
                if (box.label == KeystrokesLayout.ID_LMB || box.label == KeystrokesLayout.ID_RMB) {
                    // Mouse box: label in the upper half, our "<n> CPS" counter under it at half scale.
                    val tw = font.getStringWidth(box.label)
                    font.drawStringWithShadow(box.label, (x + w / 2f - tw / 2f).toInt(), (y + 3f).toInt(), labelColor)
                    if (showCps) {
                        val cps = if (box.label == KeystrokesLayout.ID_LMB) EventBridge.leftCps() else EventBridge.rightCps()
                        if (cps > 0) drawCps(ctx, box, "$cps CPS", factor)
                    }
                } else {
                    val tw = font.getStringWidth(box.label)
                    font.drawStringWithShadow(box.label, (x + w / 2f - tw / 2f).toInt(), (y + h / 2f - 4f).toInt(), labelColor)
                }
            }
        } finally {
            g.glPopMatrix()
        }
    }

    /**
     * The half-scale white CPS counter, drawn in the doubled coordinate space the 0.5 scale needs
     * (the same trick the pre-Lunar renderer used) so it sits centred in the lower half of the box.
     */
    private fun drawCps(ctx: RenderContext, box: KeystrokesLayout.Box, text: String, factor: Double) {
        val font = ctx.fontRenderer
        val textWidth = font.getStringWidth(text)
        val g = ctx.gl
        g.glPushMatrix()
        try {
            g.glScalef(0.5f, 0.5f, 0.5f)
            val cx = (box.localX + box.localWidth / 2f) * 2f - textWidth / 2f
            val cy = (box.localY + box.localHeight / 2f + 2f) * 2f
            val white = (255.0 * factor).toInt()
            font.drawStringWithShadow(text, cx.toInt(), cy.toInt(), 0xFF000000.toInt() or (white shl 16) or (white shl 8) or white)
        } finally {
            g.glPopMatrix()
        }
    }

    /** Label/line colour: the theme RGB dimmed by the press factor, on a solid black alpha. */
    private fun textColor(theme: Int, factor: Double): Int {
        val q = (theme shr 16) and 255
        val r = (theme shr 8) and 255
        val s = theme and 255
        return 0xFF000000.toInt() or ((q * factor).toInt() shl 16) or ((r * factor).toInt() shl 8) or (s * factor).toInt()
    }

    private fun themeColor(): Int = when (colorIndex) {
        "Red" -> THEME_COLORS[1]
        "Green" -> THEME_COLORS[2]
        "Blue" -> THEME_COLORS[3]
        "Yellow" -> THEME_COLORS[4]
        "Purple" -> THEME_COLORS[5]
        "Rainbow" -> java.awt.Color.getHSBColor((System.currentTimeMillis() % 3750L) / 3750f, 1f, 1f).rgb
        else -> THEME_COLORS[0]
    }
}
