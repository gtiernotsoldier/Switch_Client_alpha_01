package io.doppel.adapter.common.render

import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Smooth font renderer — a zero-Minecraft-dependency CFont-style glyph atlas
 * (the Nemui technique).
 *
 * Glyphs (chars 0..255) are rasterized with [java.awt.Font] into a single
 * 1024x1024 [BufferedImage] atlas at construction time, then uploaded as an
 * OpenGL texture through [GL11Bridge] and drawn as textured quads.
 *
 * v3 (HUD landing) changes versus the first attempt that was rolled back:
 *  - **Scale-aware rasterization**: the atlas is built at `guiSize * renderScale`
 *    pixels and drawn back at `guiSize` GUI units, so glyphs are sampled
 *    ~1:1 against physical pixels and stay crisp at every GUI scale.
 *  - **Real metrics**: [fontHeight] derives from the tallest rasterized glyph
 *    instead of being hardcoded to vanilla's 9.
 *  - **Upload reliability ladder**: primary path is a manual, fully-controlled
 *    LWJGL upload (direct ByteBuffer, correct ARGB→RGBA byte order, GL_LINEAR +
 *    CLAMP — the old "manual path" failure was a non-direct buffer); fallback is
 *    MC's TextureUtil. A failed upload is retried with backoff instead of being
 *    sticky-forever, and a failed atlas can be prepared again via [prepare].
 *  - **Re-assert**: [ensureTexture] re-uploads the atlas periodically, healing
 *    the texture after MC resource reloads (F3+T deletes all GL textures).
 *  - **Mirrored glyphs**: [drawCharMirrored] renders a horizontally flipped
 *    character (the ᗡ of the DOPPEL logo mark) via matrix tricks.
 *
 * This type has no dependency on Minecraft, LWJGL, or the agent classloader —
 * everything arrives through [GL11Bridge] and a [java.awt.Font].
 */
class SmoothFontRenderer(
    font: Font,
    private val gl: GL11Bridge,
    /** Intended font size in GUI pixels (the layout size you draw at). */
    val guiSize: Float = 9f,
    /** Raster multiplier = current GUI scale factor; raster px = guiSize * renderScale. */
    private val renderScale: Int = 2,
    /** Extra empty space per glyph cell (guards against linear-filter bleeding). */
    private val glyphPadding: Int = 6
) : FontRendererBridge {

    private class Glyph(val widthPx: Int, val heightPx: Int, val storedX: Int, val storedY: Int)

    private val glyphs = arrayOfNulls<Glyph>(256)
    private val atlasSize = 1024

    private var maxGlyphHeightPx = 1

    /** Line height in GUI pixels, derived from real glyph metrics. */
    override val fontHeight: Int

    private var textureId: Int = -1
    private var uploadFails: Int = 0
    private var framesSinceAssert: Int = 0

    private companion object {
        /** Throttled retry cadence (prepare() calls between upload attempts). */
        const val RETRY_CALLS = 120
    }

    /** True once the atlas texture is live on the GPU. */
    val ready: Boolean get() = textureId > 0

    /** The rasterized glyph atlas (white glyphs + AA on transparent), kept for re-uploads. */
    private val atlasImage: BufferedImage

    init {
        val img = BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB)
        val graphics = img.createGraphics() as Graphics2D
        try {
            graphics.setFont(font)
            graphics.color = java.awt.Color.WHITE
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            val metrics = graphics.fontMetrics
            var positionX = 0
            var positionY = 1
            var rowHeight = 0

            for (code in 0..255) {
                val c = code.toChar()
                val bounds: Rectangle2D = metrics.getStringBounds(c.toString(), graphics)
                val glyphWidth = bounds.bounds.width + glyphPadding
                val glyphHeight = bounds.bounds.height

                if (positionX + glyphWidth >= atlasSize) {
                    positionX = 0
                    positionY += rowHeight
                    rowHeight = 0
                }
                if (positionY + glyphHeight >= atlasSize) break // atlas full — beyond Latin-1 unused for HUD
                if (glyphHeight > rowHeight) rowHeight = glyphHeight
                if (glyphHeight > maxGlyphHeightPx) maxGlyphHeightPx = glyphHeight

                glyphs[code] = Glyph(glyphWidth, glyphHeight, positionX, positionY)
                graphics.drawString(c.toString(), positionX + glyphPadding / 2, positionY + metrics.ascent)
                positionX += glyphWidth
            }
        } finally {
            graphics.dispose()
        }
        atlasImage = img
        // Visible ink height in GUI px (descent of control chars inflates the max; clamp sane).
        fontHeight = ((maxGlyphHeightPx + renderScale - 1) / renderScale).coerceIn(4, 64)
    }

    /**
     * Ensure the atlas texture exists. Returns true when the texture is live.
     * Safe to call repeatedly; failed uploads retry with a small backoff and
     * the atlas is periodically re-asserted to survive resource reloads.
     */
    fun prepare(): Boolean {
        if (textureId > 0) return true
        // Retry FOREVER, throttled: after a failed attempt, wait [RETRY_CALLS]
        // calls before the next try. A transient GL state (world still loading,
        // context busy) must never kill smooth text for the whole session —
        // the old "10 tries then permanent give-up" made fonts silently
        // vanish forever with zero log (the "header but no list" bug).
        if (uploadFails > 0 && uploadFails % RETRY_CALLS != 0) return false
        val id = try {
            gl.uploadFontTexture(atlasImage)
        } catch (e: Exception) {
            uploadFails++
            if (uploadFails == 1) io.doppel.core.logging.CoreLogger.warn(
                "[SmoothFont] atlas upload threw ${e.javaClass.simpleName}: ${e.message} — retrying throttled")
            return false
        }
        if (id <= 0) {
            uploadFails++
            if (uploadFails == 1) io.doppel.core.logging.CoreLogger.warn(
                "[SmoothFont] atlas upload refused (guiSize=$guiSize) — retrying throttled")
            return false
        }
        textureId = id
        uploadFails = 0
        io.doppel.core.logging.CoreLogger.info("[SmoothFont] atlas uploaded (guiSize=$guiSize, tex=$id)")
        return true
    }

    /** Per-frame tick: lazily (re)upload, and re-assert the texture periodically. */
    private fun ensureTexture(): Boolean {
        if (!prepare()) return false
        if (++framesSinceAssert >= 900) {
            // Heal the texture after MC resource reloads delete raw GL textures.
            framesSinceAssert = 0
            gl.uploadFontTextureInto(textureId, atlasImage)
        }
        return true
    }

    /**
     * Free the GPU texture. Called when the owning bundle is rebuilt
     * (GUI scale change) — without this, every rebuild leaks 4 MB of
     * VRAM per atlas (no other owner can delete the texture name).
     */
    fun release() {
        if (textureId > 0) {
            gl.glDeleteTextures(textureId)
            textureId = -1
        }
    }

    override fun drawStringWithShadow(text: String, x: Int, y: Int, color: Int): Int {
        if (!ensureTexture()) return getStringWidth(text)
        val alpha = (color ushr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        bindForGlyphs()
        // Shadow pass (+1 GUI px, color * 0.25) then foreground pass.
        gl.glColor4f(r / 255f * 0.25f, g / 255f * 0.25f, b / 255f * 0.25f, alpha / 255f)
        drawGlyphs(text, x + 1f, y + 1f)
        gl.glColor4f(r / 255f, g / 255f, b / 255f, alpha / 255f)
        drawGlyphs(text, x.toFloat(), y.toFloat())
        endGlyphs()
        return getStringWidth(text)
    }

    /**
     * Draw a single character mirrored horizontally (logo mark "ᗡ").
     * Matrix-based flip, no shadow pass — decorative use only.
     */
    fun drawCharMirrored(ch: Char, x: Float, y: Float, color: Int): Float {
        if (!ensureTexture()) return getStringWidth(ch.toString()).toFloat()
        val glyph = glyphs[ch.code.coerceIn(0, 255)] ?: return 0f
        val w = glyph.widthPx / renderScale.toFloat()
        bindForGlyphs()
        gl.glPushMatrix()
        gl.glTranslatef(x + w, y, 0f)
        gl.glScalef(-1f, 1f, 1f)
        gl.glColor4f(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color ushr 24) and 0xFF) / 255f
        )
        drawGlyphs(ch.toString(), 0f, 0f)
        gl.glPopMatrix()
        endGlyphs()
        return w
    }

    /** Bind atlas + set up GL state for textured glyph quads (GL_MODULATE tinting). */
    private fun bindForGlyphs() {
        gl.glBindTexture(textureId)
        gl.glEnable(GLConstants.GL_TEXTURE_2D)
        gl.glEnable(GLConstants.GL_BLEND)
        gl.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
        gl.glDisable(GLConstants.GL_DEPTH_TEST)
        gl.glDisable(GLConstants.GL_ALPHA_TEST)
        // glColor4f must tint the atlas (white glyphs = color mask), not replace it.
        gl.glTexEnv(GLConstants.GL_TEXTURE_ENV, GLConstants.GL_TEXTURE_ENV_MODE, GLConstants.GL_MODULATE)
    }

    /**
     * Symmetric tail of [bindForGlyphs] — restores exactly what it changed.
     *
     * v3.3: the old tail hardcoded `depthMask(true) + enable(DEPTH_TEST)`
     * after every string — re-enabling depth test MID-HUD-FRAME (the outer
     * OverlayRenderer had just disabled it), so untextured quads drawn after
     * text could be occluded by world geometry. Depth state belongs to the
     * frame owner; this renderer now only restores what IT touched:
     * alpha test and the atlas binding (texture bindings are NOT covered by
     * glPushAttrib — a leaked atlas binding would be sampled by whatever
     * textured draw comes next, vanilla FontRenderer included).
     */
    private fun endGlyphs() {
        gl.glEnable(GLConstants.GL_ALPHA_TEST)
        gl.glBindTexture(0)
    }

    private fun drawGlyphs(text: String, startX: Float, startY: Float) {
        var cx = startX
        val baseY = startY
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u00a7') { i += 2; continue }
            val code = ch.code
            if (code > 255) { i++; continue }
            val glyph = glyphs[code]
            if (glyph == null) { i++; continue }

            // Full padded cell, CFont-style: ink sits padHalf raster px inside the
            // cell; advance includes the padding (a constant per-glyph tracking).
            val cellW = glyph.widthPx / renderScale.toFloat()
            val cellH = glyph.heightPx / renderScale.toFloat()
            val x0 = glyph.storedX.toFloat() / atlasSize
            val x1 = (glyph.storedX + glyph.widthPx).toFloat() / atlasSize
            val vMin = glyph.storedY.toFloat() / atlasSize
            val vMax = (glyph.storedY + glyph.heightPx).toFloat() / atlasSize

            gl.glBegin(GLConstants.GL_QUADS)
            gl.glTexCoord2f(x0, vMin); gl.glVertex2f(cx, baseY)
            gl.glTexCoord2f(x1, vMin); gl.glVertex2f(cx + cellW, baseY)
            gl.glTexCoord2f(x1, vMax); gl.glVertex2f(cx + cellW, baseY + cellH)
            gl.glTexCoord2f(x0, vMax); gl.glVertex2f(cx, baseY + cellH)
            gl.glEnd()

            cx += cellW
            i++
        }
    }

    override fun getStringWidth(text: String): Int {
        var width = 0f
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u00a7') { i += 2; continue }
            val code = ch.code
            if (code > 255) { i++; continue }
            val glyph = glyphs[code]
            if (glyph != null) width += glyph.widthPx / renderScale.toFloat()
            i++
        }
        return Math.round(width)
    }
}
