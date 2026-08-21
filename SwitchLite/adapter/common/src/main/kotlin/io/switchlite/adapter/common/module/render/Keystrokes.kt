package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext
import io.switchlite.adapter.common.render.GLConstants
import io.switchlite.adapter.common.ui.RenderUtils

/**
 * Keystrokes — in-game key/button press indicator. A faithful, pixel-exact port of
 * Raven-bPLUS's keystrokes display.
 *
 * Layout (unscaled, from Raven's KeySrokeRenderer/KeyStrokeKeyRenderer/KeyStrokeMouse):
 *   W  at (26, 2);   A at (2, 26);  S at (26, 26);  D at (50, 26)   — each 22x22
 *   LMB at (2, 50) width 34;  RMB at (38, 50) width 34              — 34x22
 *   SPACE at (2, 74) width 70  (two mouse-button widths + gap)      — 70x22
 *
 * Press animation (Raven): on press the background lights up (g 0→255) and the text
 * shifts to black (h 1→0); on release it fades back. A CPS counter renders under each
 * mouse button at 0.5x scale (Raven's glScalef trick), white text.
 *
 * Configurable (WebUI): Scale, Text color (White/Red/Green/Blue/Yellow/Purple/Rainbow),
 * Show mouse buttons, Outline. Position is drag-adjustable in-game (only while a GUI
 * screen is open / paused).
 */
object Keystrokes : Module("Keystrokes", Category.RENDER) {

    // ── Raven color palette (same order as Raven's KeyStrokeConfigGui) ──
    private val THEME_COLORS = intArrayOf(
        0xFFFFFF,  // White
        0xFF0000,  // Red
        0x00FF00,  // Green
        0x0000FF,  // Blue
        0xFFFF00,  // Yellow
        0xAA00AA,  // Purple
        -1         // Rainbow (computed per frame)
    )

    // ── Position / size ──

    @Volatile
    var posX: Int = 8
        private set
    @Volatile
    var posY: Int = 8
        private set

    /** Widget scale factor (1.0 = 100%). */
    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    /** Text color index into [THEME_COLORS] (0..6, 6 = Rainbow). */
    var colorIndex by choices("TextColor", arrayOf("White", "Red", "Green", "Blue", "Yellow", "Purple", "Rainbow"))

    /** Whether to show the mouse buttons row (LMB/RMB). */
    var showMouse by boolean("ShowMouse", true)

    /** Whether to draw the 1px outline around each key. */
    var outline by boolean("Outline", false)

    // ── Drag state ──

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // ── Per-key animation state (Raven: track state change time) ──
    private class KeyAnim {
        var wasDown = false
        var lastChange = 0L
    }
    private val keyAnims = HashMap<String, KeyAnim>()

    private fun anim(label: String): KeyAnim = keyAnims.getOrPut(label) { KeyAnim() }

    // ── Raven dimensions (scaled by `scale`) ──
    private val KEY_W = 22f
    private val KEY_H = 22f
    private val MOUSE_W = 34f
    private val MOUSE_H = 22f
    private val SPACE_W = 70f
    private val SPACE_H = 22f

    private fun k(v: Int): Float = v * scale

    /** Total widget size in scaled px. */
    private fun widgetWidth(): Int = (k(74)).toInt()
    private fun widgetHeight(): Int = (k(if (showMouse) 96 else 72)).toInt()

    /**
     * Render the keystrokes widget. Called from OverlayRenderer on the MC render
     * thread. Handles drag + draw.
     */
    fun render(ctx: RenderContext) {
        if (!enabled) return
        handleDrag(ctx)
        draw(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drag (free placement, only while paused / GUI open)
    // ═══════════════════════════════════════════

    private fun handleDrag(ctx: RenderContext) {
        if (!EventBridge.isGuiOpen) {
            dragging = false
            return
        }
        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY
        val leftDown = EventBridge.guiLeftMouseDown

        val w = widgetWidth()
        val h = widgetHeight()

        if (leftDown) {
            if (!dragging) {
                if (mx in posX until posX + w && my in posY until posY + h) {
                    dragging = true
                    dragOffsetX = mx - posX
                    dragOffsetY = my - posY
                }
            } else {
                posX = mx - dragOffsetX
                posY = my - dragOffsetY
                clampToScreen(ctx)
            }
        } else {
            dragging = false
        }
    }

    private fun clampToScreen(ctx: RenderContext) {
        if (posX < 0) posX = 0
        if (posY < 0) posY = 0
        if (posX + widgetWidth() > ctx.scaledWidth) posX = ctx.scaledWidth - widgetWidth()
        if (posY + widgetHeight() > ctx.scaledHeight) posY = ctx.scaledHeight - widgetHeight()
    }

    // ═══════════════════════════════════════════
    //  Drawing (faithful Raven port)
    // ═══════════════════════════════════════════

    private fun draw(ctx: RenderContext) {
        val font = ctx.fontRenderer
        val x = posX.toFloat()
        val y = posY.toFloat()

        // Theme color (int) — Rainbow computed per frame.
        val theme = when (colorIndex) {
            "Red" -> THEME_COLORS[1]
            "Green" -> THEME_COLORS[2]
            "Blue" -> THEME_COLORS[3]
            "Yellow" -> THEME_COLORS[4]
            "Purple" -> THEME_COLORS[5]
            "Rainbow" -> rainbowColor()
            else -> THEME_COLORS[0]
        }

        // WASD cluster — 22x22 keys, Raven coords, text at +8,+8.
        drawKey(ctx, font, "W", x + k(26), y + k(2), KEY_W, KEY_H, EventBridge.isKeyForwardDown, theme, textY = k(8))
        drawKey(ctx, font, "A", x + k(2), y + k(26), KEY_W, KEY_H, EventBridge.isKeyLeftDown, theme, textY = k(8))
        drawKey(ctx, font, "S", x + k(26), y + k(26), KEY_W, KEY_H, EventBridge.isKeyBackDown, theme, textY = k(8))
        drawKey(ctx, font, "D", x + k(50), y + k(26), KEY_W, KEY_H, EventBridge.isKeyRightDown, theme, textY = k(8))

        if (showMouse) {
            val mouseY = y + k(50)
            // Mouse buttons — 34x22, text at +8,+4 (Raven KeyStrokeMouse).
            drawKey(ctx, font, "LMB", x + k(2), mouseY, MOUSE_W, MOUSE_H, EventBridge.isLeftMousePhysicallyDown, theme, textY = k(4))
            drawKey(ctx, font, "RMB", x + k(38), mouseY, MOUSE_W, MOUSE_H, EventBridge.isRightMousePhysicallyDown, theme, textY = k(4))

            // CPS counters under each mouse button (Raven: 0.5x scale, white, centered).
            drawCps(ctx, font, EventBridge.leftCps(), x + k(2), mouseY, theme)
            drawCps(ctx, font, EventBridge.rightCps(), x + k(38), mouseY, theme)

            // Jump key (SPACE): full-width 70px (two mouse widths + gap), centered line.
            drawSpace(ctx, font, x + k(2), y + k(74), SPACE_W, SPACE_H, EventBridge.isKeyJumpDown, theme)
        } else {
            // No mouse row: spacebar sits right below WASD.
            drawSpace(ctx, font, x + k(2), y + k(50), SPACE_W, SPACE_H, EventBridge.isKeyJumpDown, theme)
        }
    }

    /**
     * Raven's key rendering: background lights up while held (g 0→255 over the press),
     * text color fades to black while held (h 1→0 then 0→1 on release). The press uses
     * g = 2*elapsed (128ms to full bright) and h = 1 - elapsed/20 (20ms to black text),
     * giving the "clicked" feel that varies with CPS.
     */
    private fun drawKey(
        ctx: RenderContext,
        font: io.switchlite.adapter.common.render.FontRendererBridge,
        label: String, x: Float, y: Float, w: Float, h: Float,
        down: Boolean, theme: Int, textY: Float
    ) {
        val a = anim(label)
        if (down != a.wasDown) {
            a.wasDown = down
            a.lastChange = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - a.lastChange

        val g: Int
        val f: Double  // text brightness factor (0 = black text)
        if (down) {
            g = kotlin.math.min(255, (2 * elapsed).toInt())
            f = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            g = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            f = kotlin.math.min(1.0, elapsed / 20.0)
        }

        // Background: 0x78 (120/255 alpha) base + white level g (Raven 2013265920 + g).
        val bg = 0x78000000.toInt() or (g shl 16) or (g shl 8) or g
        RenderUtils.rect(ctx, x, y, w, h, bg)

        if (outline) {
            RenderUtils.rect(ctx, x, y, w, 1f, theme)
            RenderUtils.rect(ctx, x, y, 1f, h, theme)
            RenderUtils.rect(ctx, x, y + h - 1f, w, 1f, theme)
            RenderUtils.rect(ctx, x + w - 1f, y, 1f, h, theme)
        }

        // Text color: alpha 0xFF + theme RGB scaled by f. When f→0 the text becomes pure
        // black (0xFF000000) — on the now-bright background that is the "pressed" look
        // (black text on white-ish key), guaranteeing it stays visible.
        val r = ((theme shr 16) and 0xFF)
        val gg = ((theme shr 8) and 0xFF)
        val b = (theme and 0xFF)
        val textColor = 0xFF000000.toInt() or ((r * f).toInt() shl 16) or ((gg * f).toInt() shl 8) or (b * f).toInt()

        // Raven fixed text offsets: key label at (x+8, y+textY).
        font.drawStringWithShadow(label, (x + k(8)).toInt(), (y + textY).toInt(), textColor)
    }

    /**
     * Draw the spacebar: a 70px-wide key whose body is a single horizontal line centered
     * both horizontally and vertically (like a real SPACE keycap). Uses the same
     * background/press animation as [drawKey].
     */
    private fun drawSpace(
        ctx: RenderContext,
        font: io.switchlite.adapter.common.render.FontRendererBridge,
        x: Float, y: Float, w: Float, h: Float, down: Boolean, theme: Int
    ) {
        val a = anim("SPACE")
        if (down != a.wasDown) {
            a.wasDown = down
            a.lastChange = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - a.lastChange

        val g: Int
        val f: Double
        if (down) {
            g = kotlin.math.min(255, (2 * elapsed).toInt())
            f = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            g = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            f = kotlin.math.min(1.0, elapsed / 20.0)
        }

        val bg = 0x78000000.toInt() or (g shl 16) or (g shl 8) or g
        RenderUtils.rect(ctx, x, y, w, h, bg)
        if (outline) {
            RenderUtils.rect(ctx, x, y, w, 1f, theme)
            RenderUtils.rect(ctx, x, y, 1f, h, theme)
            RenderUtils.rect(ctx, x, y + h - 1f, w, 1f, theme)
            RenderUtils.rect(ctx, x + w - 1f, y, 1f, h, theme)
        }

        // The spacebar line: centered horizontally and vertically, ~70% of key width.
        val r = ((theme shr 16) and 0xFF)
        val gg = ((theme shr 8) and 0xFF)
        val b = (theme and 0xFF)
        val lineColor = 0xFF000000.toInt() or ((r * f).toInt() shl 16) or ((gg * f).toInt() shl 8) or (b * f).toInt()
        val lineW = w * 0.70f
        val lineH = (2f * scale).coerceAtLeast(1f)
        val lx = x + (w - lineW) / 2f
        val ly = y + (h - lineH) / 2f
        RenderUtils.rect(ctx, lx, ly, lineW, lineH, lineColor)
    }

    /**
     * Draw the CPS counter under a mouse button, Raven-style: scaled to 0.5x (small),
     * horizontally centered on the button, bright white text. Only drawn when CPS > 0.
     */
    private fun drawCps(
        ctx: RenderContext,
        font: io.switchlite.adapter.common.render.FontRendererBridge,
        cps: Int, x: Float, y: Float, theme: Int
    ) {
        if (cps <= 0) return
        val text = "$cps CPS"
        val textWidth = font.getStringWidth(text)

        val g = ctx.gl
        g.glPushMatrix()
        try {
            // Raven: glScalef(0.5) then draw at (buttonCenter*2 - textWidth/2, (y+14)*2).
            g.glScalef(0.5f, 0.5f, 0.5f)
            val centerX = x + k(17)          // button center (Raven uses +17 for 34-wide)
            val tx = ((centerX) * 2f - textWidth / 2f).toInt()
            val ty = ((y + k(14)) * 2f).toInt()
            font.drawStringWithShadow(text, tx, ty, 0xFFFFFFFF.toInt())
        } finally {
            g.glPopMatrix()
        }
    }

    private fun rainbowColor(): Int {
        val hue = (System.currentTimeMillis() % 3750L) / 3750f
        return java.awt.Color.getHSBColor(hue, 1f, 1f).rgb
    }
}
