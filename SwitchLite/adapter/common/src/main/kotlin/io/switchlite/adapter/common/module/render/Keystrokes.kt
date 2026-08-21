package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext
import io.switchlite.adapter.common.ui.RenderUtils

/**
 * Keystrokes — in-game key/button press indicator, a faithful port of Raven's
 * keystrokes display (layout, background, press animation, colors, CPS counter).
 *
 * Layout (Raven, unscaled):
 *   ┌───┐
 *   │ W │        W at (26,2); A/S/D at (2,26),(26,26),(50,26);
 *   │A S D│      LMB/RMB at (2,50),(38,50); SPACE full-width below.
 *   │LMB RMB│
 *   │ SPACE │
 * Each key 22x22, cell pitch 24. Background darkens; while a key is held it
 * lights up (bright) with a fast press animation; the text shifts to the theme
 * color while held. A CPS counter renders under the mouse buttons.
 *
 * Configurable (WebUI): Scale, Text color (White/Red/Green/Blue/Yellow/Purple/
 * Rainbow), Show mouse buttons, Outline. Position is drag-adjustable in-game by
 * holding left mouse on the widget.
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

    // ── Sizes (scaled) ──
    private val KEY = 22f
    private val PITCH = 24f

    private fun k(v: Int): Float = v * scale

    private fun keySize(): Float = KEY * scale
    private fun pitch(): Float = PITCH * scale

    /** Total widget size in scaled px. */
    private fun widgetWidth(): Int = (k(74)).toInt()
    private fun widgetHeight(): Int = (k(if (showMouse) 98 else 74)).toInt()

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
    //  Drag (free placement, like Raven's config GUI)
    // ═══════════════════════════════════════════

    private fun handleDrag(ctx: RenderContext) {
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
        val x = posX
        val y = posY
        val s = keySize()

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

        // WASD cluster (Raven coords).
        drawKey(ctx, font, "W", x + k(26), y + k(2), s, EventBridge.isKeyForwardDown, theme)
        drawKey(ctx, font, "A", x + k(2), y + k(26), s, EventBridge.isKeyLeftDown, theme)
        drawKey(ctx, font, "S", x + k(26), y + k(26), s, EventBridge.isKeyBackDown, theme)
        drawKey(ctx, font, "D", x + k(50), y + k(26), s, EventBridge.isKeyRightDown, theme)

        if (showMouse) {
            val mouseY = y + k(50)
            drawKey(ctx, font, "LMB", x + k(2), mouseY, s, EventBridge.isLeftMousePhysicallyDown, theme)
            drawKey(ctx, font, "RMB", x + k(38), mouseY, s, EventBridge.isRightMousePhysicallyDown, theme)

            // CPS counters under each mouse button (Raven shows "N CPS").
            val lmbCps = EventBridge.leftCps()
            val rmbCps = EventBridge.rightCps()
            if (lmbCps > 0) font.drawStringWithShadow("$lmbCps CPS", (x + k(2)).toInt(), (mouseY + s + 2).toInt(), theme)
            if (rmbCps > 0) font.drawStringWithShadow("$rmbCps CPS", (x + k(38)).toInt(), (mouseY + s + 2).toInt(), theme)

            // Jump key (SPACE) full-width below mouse row.
            drawKey(ctx, font, "SPACE", x + k(2), y + k(74), s * 2 + k(2), EventBridge.isKeyJumpDown, theme, wide = true)
        } else {
            // No mouse row: SPACE sits right below WASD.
            drawKey(ctx, font, "SPACE", x + k(2), y + k(50), s * 2 + k(2), EventBridge.isKeyJumpDown, theme, wide = true)
        }
    }

    /**
     * Raven's key rendering: background lights up while held (g 0→255 over the
     * press), text color fades to the theme while held (h 1→0 then 0→1 on release).
     */
    private fun drawKey(
        ctx: RenderContext,
        font: io.switchlite.adapter.common.render.FontRendererBridge,
        label: String, x: Int, y: Int, size: Float, down: Boolean, theme: Int
    ) {
        val w = size
        val h = size

        val a = anim(label)
        if (down != a.wasDown) {
            a.wasDown = down
            a.lastChange = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - a.lastChange

        val g: Int
        val f: Double  // text brightness factor
        if (down) {
            g = kotlin.math.min(255, (2 * elapsed).toInt())
            f = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            g = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            f = kotlin.math.min(1.0, elapsed / 20.0)
        }

        // Background: 0x78 (120/255 alpha) base + white level g (Raven 2013265920 + g).
        val bg = 0x78000000.toInt() or (g shl 16) or (g shl 8) or g
        RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), w, h, bg)

        if (outline) {
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), w, 1f, theme)
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), 1f, h, theme)
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat() + h - 1f, w, 1f, theme)
            RenderUtils.rect(ctx, x.toFloat() + w - 1f, y.toFloat(), 1f, h, theme)
        }

        // Text color: alpha 0xFF + theme RGB scaled by f (Raven -16777216 + ...).
        val r = ((theme shr 16) and 0xFF)
        val gg = ((theme shr 8) and 0xFF)
        val b = (theme and 0xFF)
        val textColor = 0xFF000000.toInt() or ((r * f).toInt() shl 16) or ((gg * f).toInt() shl 8) or (b * f).toInt()

        val textW = font.getStringWidth(label)
        val tx = x + ((w - textW) / 2f).toInt()
        val ty = y + ((h - font.fontHeight) / 2f).toInt()
        font.drawStringWithShadow(label, tx, ty, textColor)
    }

    private fun rainbowColor(): Int {
        val hue = (System.currentTimeMillis() % 3750L) / 3750f
        return java.awt.Color.getHSBColor(hue, 1f, 1f).rgb
    }
}
