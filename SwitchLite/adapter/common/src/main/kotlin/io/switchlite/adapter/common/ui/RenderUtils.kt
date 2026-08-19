package io.switchlite.adapter.common.ui

import io.switchlite.adapter.common.render.FontRendererBridge
import io.switchlite.adapter.common.render.GL11Bridge
import io.switchlite.adapter.common.render.GLConstants
import io.switchlite.adapter.common.render.RenderContext

/**
 * Immediate-mode drawing helpers used by OverlayRenderer.
 *
 * All methods follow the same GL contract: they set blend/depth state,
 * draw, then restore TEXTURE_2D and depth mask. The full state is
 * additionally saved/restored by the caller's glPushAttrib.
 */
object RenderUtils {

    /** Number of arc segments per 90° corner. Higher = smoother (Nemui's
     *  shader SDF is perfectly smooth; we approximate with more segments). */
    private const val CORNER_SEGMENTS = 10

    // ═══════════════════ Filled rectangle ═══════════════════

    fun rect(ctx: RenderContext, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val g = ctx.gl
        prepareColor(g, color)
        g.glBegin(GLConstants.GL_QUADS)
        g.glVertex2f(x, y + h)
        g.glVertex2f(x + w, y + h)
        g.glVertex2f(x + w, y)
        g.glVertex2f(x, y)
        g.glEnd()
        restoreColor(g)
    }

    // ═══════════════════ Rounded rectangle ═══════════════════

    /**
     * Rounded rectangle: 5 QUADS (center + 4 edge strips) + 4 corner fans
     * (GL_TRIANGLE_FAN, 90° each). Falls back to a plain rect when the
     * radius is negligible or exceeds half the smaller dimension.
     */
    fun roundedRect(ctx: RenderContext, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        val g = ctx.gl
        val rr = r.coerceIn(0f, kotlin.math.min(w, h) / 2f)
        if (rr <= 0.5f) {
            rect(ctx, x, y, w, h, color)
            return
        }
        prepareColor(g, color)

        // Center + four edge strips.
        g.glBegin(GLConstants.GL_QUADS)
        // center
        g.glVertex2f(x + rr, y + rr)
        g.glVertex2f(x + w - rr, y + rr)
        g.glVertex2f(x + w - rr, y + h - rr)
        g.glVertex2f(x + rr, y + h - rr)
        // top strip
        g.glVertex2f(x + rr, y)
        g.glVertex2f(x + w - rr, y)
        g.glVertex2f(x + w - rr, y + rr)
        g.glVertex2f(x + rr, y + rr)
        // bottom strip
        g.glVertex2f(x + rr, y + h - rr)
        g.glVertex2f(x + w - rr, y + h - rr)
        g.glVertex2f(x + w - rr, y + h)
        g.glVertex2f(x + rr, y + h)
        // left strip
        g.glVertex2f(x, y + rr)
        g.glVertex2f(x + rr, y + rr)
        g.glVertex2f(x + rr, y + h - rr)
        g.glVertex2f(x, y + h - rr)
        // right strip
        g.glVertex2f(x + w - rr, y + rr)
        g.glVertex2f(x + w, y + rr)
        g.glVertex2f(x + w, y + h - rr)
        g.glVertex2f(x + w - rr, y + h - rr)
        g.glEnd()

        // Four 90° corner fans.
        cornerFan(g, x + rr, y + rr, rr, 180.0, 270.0)        // top-left
        cornerFan(g, x + w - rr, y + rr, rr, 270.0, 360.0)    // top-right
        cornerFan(g, x + w - rr, y + h - rr, rr, 0.0, 90.0)   // bottom-right
        cornerFan(g, x + rr, y + h - rr, rr, 90.0, 180.0)     // bottom-left

        restoreColor(g)
    }

    private fun cornerFan(g: GL11Bridge, cx: Float, cy: Float, r: Float, startDeg: Double, endDeg: Double) {
        g.glBegin(GLConstants.GL_TRIANGLE_FAN)
        g.glVertex2f(cx, cy)
        for (i in 0..CORNER_SEGMENTS) {
            val theta = Math.toRadians(startDeg + (endDeg - startDeg) * i / CORNER_SEGMENTS)
            val vx = cx + (kotlin.math.cos(theta) * r).toFloat()
            val vy = cy + (kotlin.math.sin(theta) * r).toFloat()
            g.glVertex2f(vx, vy)
        }
        g.glEnd()
    }

    /**
     * Rounded-rectangle outline (border) of [width] px. Draws a filled rounded
     * rect in [color], then a smaller inset rounded rect punched with the
     * caller-provided fill, leaving a ring. Pass [fill] = the panel background
     * color so the border reads as a crisp outline.
     */
    fun roundedRectOutline(
        ctx: RenderContext,
        x: Float, y: Float, w: Float, h: Float, r: Float,
        color: Int, width: Float = 1f, fill: Int = 0x00000000
    ) {
        // Outer (border) ring
        roundedRect(ctx, x, y, w, h, r, color)
        // Inner punch (only if we have an opaque-ish fill to reveal the ring)
        if (fill != 0x00000000 && width < kotlin.math.min(w, h) / 2f) {
            roundedRect(ctx, x + width, y + width, w - width * 2, h - width * 2, (r - width).coerceAtLeast(0f), fill)
        }
    }

    // ═══════════════════ Aurora shadow / glow ═══════════════════

    /**
     * Soft drop shadow for a rounded card. Draws several slightly larger
     * translucent rounded rects behind the card; alpha builds toward the
     * center so it reads as a soft edge rather than a hard outline.
     */
    fun shadow(
        ctx: RenderContext,
        x: Float, y: Float, w: Float, h: Float,
        r: Float,
        depth: Int = 3,
        color: Int = 0x40000000.toInt()
    ) {
        for (i in depth downTo 1) {
            val spread = i * 1.6f
            val alphaMul = (depth - i + 1).toFloat() / (depth + 1).toFloat()
            roundedRect(
                ctx,
                x - spread, y - spread,
                w + spread * 2, h + spread * 2,
                r + spread,
                withAlpha(color, alphaMul)
            )
        }
    }

    /**
     * Outer glow around a rounded card using the given accent/glow color.
     * Stacked translucent layers create a soft neon halo.
     */
    fun glow(
        ctx: RenderContext,
        x: Float, y: Float, w: Float, h: Float,
        r: Float,
        color: Int,
        spread: Float = 6f,
        layers: Int = 4
    ) {
        for (i in layers downTo 1) {
            val s = spread * i / layers
            val alphaMul = (layers - i + 1).toFloat() / (layers + 1).toFloat()
            roundedRect(
                ctx,
                x - s, y - s,
                w + s * 2, h + s * 2,
                r + s,
                withAlpha(color, alphaMul * 0.35f)
            )
        }
    }

    /** Vertical gradient rectangle drawn as [bands] horizontal strips. */
    fun verticalGradient(
        ctx: RenderContext,
        x: Float, y: Float, w: Float, h: Float,
        topColor: Int,
        bottomColor: Int,
        bands: Int = 12
    ) {
        val step = h / bands
        for (i in 0 until bands) {
            val t = i.toFloat() / (bands - 1).coerceAtLeast(1)
            val color = lerpColor(topColor, bottomColor, t)
            rect(ctx, x, y + i * step, w, step + 1f, color)
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ta = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val aa = (a ushr 24) and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val ba = (b ushr 24) and 0xFF
        fun mix(x: Int, y: Int) = (x + (y - x) * ta).toInt()
        return (mix(aa, ba) shl 24) or (mix(ar, br) shl 16) or (mix(ag, bg) shl 8) or mix(ab, bb)
    }

    /** Apply an alpha multiplier to an ARGB color (same as Theme.withAlpha). */
    fun withAlpha(argb: Int, mul: Float): Int {
        val a = (((argb ushr 24) and 0xFF) * mul).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    // ═══════════════════ Rainbow text ═══════════════════

    /**
     * Draw text with a per-character rainbow color (Fade/Random Rainbow).
     * Avoids extending FontRendererBridge — one draw call per character,
     * x advances by each character's measured width.
     *
     * @return final x position (for chaining)
     */
    fun rainbowText(
        ctx: RenderContext,
        font: FontRendererBridge,
        text: String,
        x: Float,
        y: Float,
        phase: Int
    ): Float {
        var cx = x
        for (ch in text) {
            val s = ch.toString()
            font.drawStringWithShadow(s, cx.toInt(), y.toInt(), Theme.rainbow(phase))
            cx += kotlin.math.max(font.getStringWidth(s), 1)
        }
        return cx
    }

    // ═══════════════════ GL helpers ═══════════════════

    private fun prepareColor(g: GL11Bridge, color: Int) {
        g.glEnable(GLConstants.GL_BLEND)
        g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
        g.glDisable(GLConstants.GL_DEPTH_TEST)
        g.glDepthMask(false)
        // CRITICAL: disable ALPHA_TEST. When the ClickGUI opens as a GuiScreen,
        // MC enables GL_ALPHA_TEST and our semi-transparent quads (low alpha)
        // get discarded -> panels/toggles appear invisible while text (a real
        // texture) still shows. This is exactly "borders & toggles transparent,
        // font visible".
        g.glDisable(GLConstants.GL_ALPHA_TEST)
        g.glColor4f(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f
        )
        g.glDisable(GLConstants.GL_TEXTURE_2D)
    }

    private fun restoreColor(g: GL11Bridge) {
        g.glEnable(GLConstants.GL_TEXTURE_2D)
        g.glDepthMask(true)
    }
}
