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
 * Keystrokes — in-game key/button press indicator, a direct Kotlin port of Raven-bPLUS's
 * keystrokes display (KeyStrokeKeyRenderer + KeyStrokeMouse), preserving the exact layout,
 * dimensions, press animation and CPS counter.
 *
 * Layout (Raven, unscaled):
 *   W at (26,2); A at (2,26); S at (26,26); D at (50,26)   — 22x22 keys, text at +8,+8
 *   LMB at (2,50) width 34; RMB at (38,50) width 34        — 34x22, text at +8,+4
 *   SPACE at (28,74) width 22 (same as A/S/D), centered line
 *
 * Press animation (Raven): background i = 2*elapsed (brighten on press, fade on release),
 * text factor j = 1 - elapsed/20 on press (fade to dark), white CPS text whose brightness
 * follows j. Exactly matches Raven's KeyStrokeKeyRenderer.renderKey / KeyStrokeMouse.n.
 */
object Keystrokes : Module("Keystrokes", Category.RENDER) {

    // ── Raven color palette (KeyStrokeConfigGui order) ──
    private val THEME_COLORS = intArrayOf(
        0xFFFFFF,  // White
        0xFF0000,  // Red
        0x00FF00,  // Green
        0x0000FF,  // Blue
        0xFFFF00,  // Yellow
        0xAA00AA,  // Purple
        -1         // Rainbow (computed per frame)
    )

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

    private fun k(v: Int): Int = (v * scale).toInt()

    /** SPACE key width = A/S/D combined (3x22=66) — the full WASD row width, no wider. */
    private val SPACE_W = 66

    /** Total widget size in scaled px. */
    private fun widgetWidth(): Int = k(74)
    private fun widgetHeight(): Int = k(if (showMouse) 96 else 76)

    fun render(ctx: RenderContext) {
        if (!enabled) return
        handleDrag(ctx)
        draw(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drag (only while a GUI screen is open / paused)
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
    //  Drawing
    // ═══════════════════════════════════════════

    private fun draw(ctx: RenderContext) {
        val x = posX
        val y = posY

        val theme = when (colorIndex) {
            "Red" -> THEME_COLORS[1]
            "Green" -> THEME_COLORS[2]
            "Blue" -> THEME_COLORS[3]
            "Yellow" -> THEME_COLORS[4]
            "Purple" -> THEME_COLORS[5]
            "Rainbow" -> rainbowColor()
            else -> THEME_COLORS[0]
        }

        // WASD — Raven KeyStrokeKeyRenderer coords (26,2 / 2,26 / 26,26 / 50,26), 22x22.
        renderKey(ctx, "W", x + k(26), y + k(2), EventBridge.isKeyForwardDown, theme)
        renderKey(ctx, "A", x + k(2), y + k(26), EventBridge.isKeyLeftDown, theme)
        renderKey(ctx, "S", x + k(26), y + k(26), EventBridge.isKeyBackDown, theme)
        renderKey(ctx, "D", x + k(50), y + k(26), EventBridge.isKeyRightDown, theme)

        if (showMouse) {
            // Mouse buttons — Raven KeyStrokeMouse coords, 34x22.
            renderMouse(ctx, "LMB", 0, x + k(2), y + k(50), theme)
            renderMouse(ctx, "RMB", 1, x + k(38), y + k(50), theme)
            // SPACE — width = A/S/D combined (3x22=66, the full WASD row width), centered below.
            renderSpace(ctx, x + k(4), y + k(74), EventBridge.isKeyJumpDown, theme)
        } else {
            renderSpace(ctx, x + k(4), y + k(54), EventBridge.isKeyJumpDown, theme)
        }
    }

    // ── KeyStrokeKeyRenderer port ──

    /** Per-key animation state, exactly Raven's (e=wasDown, f=lastChange). */
    private class KeyAnim {
        var e = true
        var f = 0L
    }
    private val keyAnims = HashMap<String, KeyAnim>()
    private fun anim(label: String): KeyAnim = keyAnims.getOrPut(label) { KeyAnim() }

    /**
     * Port of KeyStrokeKeyRenderer.renderKey: 22x22 key, text at +8,+8.
     * bg = 2013265920 + (g<<16)+(g<<8)+g; text = -16777216 + themeRGB*h.
     */
    private fun renderKey(ctx: RenderContext, label: String, x: Int, y: Int, down: Boolean, color: Int) {
        val a = anim("k:$label")
        if (down != a.e) {
            a.e = down
            a.f = System.currentTimeMillis()
        }

        val elapsed = System.currentTimeMillis() - a.f
        val g: Int
        val h: Double
        if (down) {
            g = kotlin.math.min(255, (2 * elapsed).toInt())
            h = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            g = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            h = kotlin.math.min(1.0, elapsed / 20.0)
        }

        val q = (color shr 16) and 255
        val r = (color shr 8) and 255
        val s = color and 255
        val border = 0xFF000000.toInt() or (q shl 16) or (r shl 8) or s

        // Background (2013265920 = 0x78000000).
        RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(22).toFloat(), k(22).toFloat(),
            0x78000000.toInt() or (g shl 16) or (g shl 8) or g)

        if (outline) {
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(22).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), (y + k(21)).toFloat(), k(22).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
            RenderUtils.rect(ctx, (x + k(21)).toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
        }

        // Text at +8,+8; color = -16777216 + themeRGB * h.
        val textColor = 0xFF000000.toInt() or ((q * h).toInt() shl 16) or ((r * h).toInt() shl 8) or (s * h).toInt()
        ctx.fontRenderer.drawStringWithShadow(label, x + k(8), y + k(8), textColor)
    }

    // ── KeyStrokeMouse port ──

    private class MouseAnim {
        var g = true
        var h = 0L
        var f = mutableListOf<Long>()
    }
    private val mouseAnims = HashMap<Int, MouseAnim>()
    private fun mouseAnim(button: Int): MouseAnim = mouseAnims.getOrPut(button) { MouseAnim() }

    /**
     * Port of KeyStrokeMouse.n: 34x22 key, text at +8,+4, CPS counter at 0.5x scale.
     * The CPS uses glScalef(0.5) / glScalef(2.0) exactly like Raven.
     */
    private fun renderMouse(ctx: RenderContext, label: String, button: Int, x: Int, y: Int, color: Int) {
        val a = mouseAnim(button)
        val r = when (button) { 0 -> EventBridge.mouseButton0; 1 -> EventBridge.mouseButton1; else -> false }
        if (r != a.g) {
            a.g = r
            a.h = System.currentTimeMillis()
            if (r) a.f.add(a.h)
        }

        val elapsed = System.currentTimeMillis() - a.h
        val i: Int
        val j: Double
        if (r) {
            i = kotlin.math.min(255, (2 * elapsed).toInt())
            j = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            i = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            j = kotlin.math.min(1.0, elapsed / 20.0)
        }

        val t = (color shr 16) and 255
        val u = (color shr 8) and 255
        val v = color and 255
        val border = 0xFF000000.toInt() or (t shl 16) or (u shl 8) or v

        // Background 34x22.
        RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(34).toFloat(), k(22).toFloat(),
            0x78000000.toInt() or (i shl 16) or (i shl 8) or i)

        if (outline) {
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(34).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), (y + k(21)).toFloat(), k(34).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
            RenderUtils.rect(ctx, (x + k(33)).toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
        }

        // Text at +8,+4; color = -16777216 + themeRGB * j.
        val textColor = 0xFF000000.toInt() or ((t * j).toInt() shl 16) or ((u * j).toInt() shl 8) or (v * j).toInt()
        ctx.fontRenderer.drawStringWithShadow(label, x + k(8), y + k(4), textColor)

        // CPS counter — Raven: 0.5x scale, white, brightness follows j.
        val cps = when (button) { 0 -> EventBridge.leftCps(); 1 -> EventBridge.rightCps(); else -> 0 }
        if (cps > 0) {
            val text = "$cps CPS"
            val textWidth = ctx.fontRenderer.getStringWidth(text)
            val g = ctx.gl
            g.glPushMatrix()
            try {
                g.glScalef(0.5f, 0.5f, 0.5f)
                val cx = (x + k(17)) * 2 - textWidth / 2
                val cy = (y + k(14)) * 2
                val white = (255.0 * j).toInt()
                val cpsColor = 0xFF000000.toInt() or (white shl 16) or (white shl 8) or white
                ctx.fontRenderer.drawStringWithShadow(text, cx, cy, cpsColor)
            } finally {
                g.glPopMatrix()
            }
        }
    }

    // ── SPACE key (user extension) ──

    private fun renderSpace(ctx: RenderContext, x: Int, y: Int, down: Boolean, color: Int) {
        val a = anim("SPACE")
        if (down != a.e) {
            a.e = down
            a.f = System.currentTimeMillis()
        }
        val elapsed = System.currentTimeMillis() - a.f
        val g: Int
        val h: Double
        if (down) {
            g = kotlin.math.min(255, (2 * elapsed).toInt())
            h = kotlin.math.max(0.0, 1.0 - elapsed / 20.0)
        } else {
            g = kotlin.math.max(0, 255 - (2 * elapsed).toInt())
            h = kotlin.math.min(1.0, elapsed / 20.0)
        }

        RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(SPACE_W).toFloat(), k(22).toFloat(),
            0x78000000.toInt() or (g shl 16) or (g shl 8) or g)

        val q = (color shr 16) and 255
        val r = (color shr 8) and 255
        val s = color and 255
        if (outline) {
            val border = 0xFF000000.toInt() or (q shl 16) or (r shl 8) or s
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), k(SPACE_W).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), (y + k(21)).toFloat(), k(SPACE_W).toFloat(), 1f, border)
            RenderUtils.rect(ctx, x.toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
            RenderUtils.rect(ctx, (x + k(SPACE_W) - 1).toFloat(), y.toFloat(), 1f, k(22).toFloat(), border)
        }

        // Centered horizontal line (spacebar), brightness follows h.
        val lineColor = 0xFF000000.toInt() or ((q * h).toInt() shl 16) or ((r * h).toInt() shl 8) or (s * h).toInt()
        val lineW = (k(SPACE_W) * 0.7f).coerceAtLeast(6f)
        val lineH = (2f * scale).coerceAtLeast(1f)
        val lx = x + (k(SPACE_W) - lineW) / 2f
        val ly = y + (k(22) - lineH) / 2f
        RenderUtils.rect(ctx, lx, ly, lineW, lineH, lineColor)
    }

    private fun rainbowColor(): Int {
        val hue = (System.currentTimeMillis() % 3750L) / 3750f
        return java.awt.Color.getHSBColor(hue, 1f, 1f).rgb
    }
}
