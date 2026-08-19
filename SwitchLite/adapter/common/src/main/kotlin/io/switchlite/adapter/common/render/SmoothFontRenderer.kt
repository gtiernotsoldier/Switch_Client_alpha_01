package io.switchlite.adapter.common.render

import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

/**
 * Smooth font renderer — a zero-Minecraft-dependency port of the classic
 * CFont technique (used by Nemui / many clients).
 *
 * Glyphs (chars 0..255) are rasterized with [java.awt.Font] into a single
 * 1024x1024 [BufferedImage] atlas at construction time. The atlas is uploaded
 * once as an OpenGL texture (through [GL11Bridge], so this stays version
 * agnostic) and every glyph is drawn as a textured quad on demand.
 *
 * Implements [FontRendererBridge] so [OverlayRenderer] can use it as a drop-in
 * replacement for the vanilla pixel FontRenderer. It must be created on the
 * render thread (or at least lazily, since it touches GL).
 *
 * This type intentionally has no dependency on Minecraft, LWJGL, or the
 * agent classloader — everything it needs arrives through [GL11Bridge] and a
 * [java.awt.Font].
 */
class SmoothFontRenderer(
    font: Font,
    private val gl: GL11Bridge,
    /** Extra empty space per glyph (Nemui uses +8 width padding). */
    private val glyphPadding: Int = 4
) : FontRendererBridge {

    // ── Glyph atlas data ──

    private class Glyph(val width: Int, val height: Int, val storedX: Int, val storedY: Int)

    private val glyphs = arrayOfNulls<Glyph>(256)
    private val atlasSize = 1024

    /** Rendered glyph height — drives [fontHeight] and line-height layout.
     *  Kept at vanilla's 9 so OverlayRenderer's lineHeight (= fontHeight+3)
     *  stays 12 and stays in sync with ForgeBootstrap's hardcoded hit-test
     *  lineHeight. Changing this would desync ClickGUI layout vs hit-testing. */
    override val fontHeight: Int = 9

    private var textureId: Int = -1

    private val bufferedPixels: ByteBuffer

    init {
        // Rasterize glyphs into an ARGB BufferedImage (value = alpha mask,
        // we will colorize at draw time with glColor4f).
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
                if (glyphHeight > rowHeight) rowHeight = glyphHeight

                glyphs[code] = Glyph(glyphWidth, glyphHeight, positionX, positionY)
                graphics.drawString(c.toString(), positionX + 2, positionY + metrics.ascent)
                positionX += glyphWidth
            }
        } finally {
            graphics.dispose()
        }

        // Extract raw pixels for GL upload as RGBA bytes. The glyph shape
        // lives in the image's alpha; we bake it into RGB (grayscale mask)
        // and set A=255 so GL_MODULATE with glColor4f tints the glyph.
        //
        // CRITICAL: LWJGL2's glTexImage2D goes through MemoryUtil.getAddress,
        // which REQUIRES a direct buffer. ByteBuffer.wrap (heap) throws
        // IllegalArgumentException -> wrapped as InvocationTargetException.
        val rgba = ByteArray(atlasSize * atlasSize * 4)
        for (y in 0 until atlasSize) {
            for (x in 0 until atlasSize) {
                val argb = img.getRGB(x, y)
                val a = (argb ushr 24) and 0xFF
                val idx = (y * atlasSize + x) * 4
                rgba[idx] = a.toByte()
                rgba[idx + 1] = a.toByte()
                rgba[idx + 2] = a.toByte()
                rgba[idx + 3] = 255.toByte()
            }
        }
        val direct = java.nio.ByteBuffer.allocateDirect(rgba.size)
        direct.put(rgba)
        direct.flip()
        bufferedPixels = direct
    }

    private fun ensureTexture() {
        if (textureId != -1) return
        textureId = gl.glGenTextures()
        if (textureId == 0) return
        gl.glBindTexture(textureId)
        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MIN_FILTER, GLConstants.GL_LINEAR)
        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MAG_FILTER, GLConstants.GL_LINEAR)
        gl.glTexImage2DRGBA(atlasSize, atlasSize, bufferedPixels)
    }

    override fun drawStringWithShadow(text: String, x: Int, y: Int, color: Int): Int {
        ensureTexture()
        if (textureId == 0) return 0

        val alpha = (color ushr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        gl.glBindTexture(textureId)
        gl.glEnable(GLConstants.GL_TEXTURE_2D)
        gl.glEnable(GLConstants.GL_BLEND)
        gl.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
        gl.glDisable(GLConstants.GL_DEPTH_TEST)
        gl.glDepthMask(false)

        val shadowColor = (alpha shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)

        // Drop shadow pass (offset +1,+1)
        gl.glColor4f(shadowR(shadowColor), shadowG(shadowColor), shadowB(shadowColor), shadowA(shadowColor))
        drawGlyphs(text, x + 1f, y + 1f)

        // Foreground pass
        gl.glColor4f(r / 255f, g / 255f, b / 255f, alpha / 255f)
        drawGlyphs(text, x.toFloat(), y.toFloat())

        gl.glDepthMask(true)
        gl.glEnable(GLConstants.GL_DEPTH_TEST)
        return getStringWidth(text)
    }

    private fun drawGlyphs(text: String, startX: Float, startY: Float) {
        var cx = startX
        val baseY = startY
        var i = 0
        while (i < text.length) {
            var ch = text[i]
            if (ch == '\u00a7') {
                // Vanilla color/format code: skip the § and the following code char.
                i += 2
                continue
            }
            val code = ch.code
            if (code > 255) { i++; continue }
            val glyph = glyphs[code]
            if (glyph == null) { i++; continue }

            val uMin = glyph.storedX.toFloat() / atlasSize
            val uMax = (glyph.storedX + glyph.width).toFloat() / atlasSize
            // Match CFont (Nemui's proven-working renderer) exactly: glyph's
            // top (small storedY) maps to small V, no inversion. Adding a flip
            // here sampled empty texture space and made the font invisible.
            val vMin = glyph.storedY.toFloat() / atlasSize
            val vMax = (glyph.storedY + glyph.height).toFloat() / atlasSize

            gl.glBegin(GLConstants.GL_QUADS)
            gl.glTexCoord2f(uMin, vMin); gl.glVertex2f(cx, baseY)
            gl.glTexCoord2f(uMax, vMin); gl.glVertex2f(cx + glyph.width, baseY)
            gl.glTexCoord2f(uMax, vMax); gl.glVertex2f(cx + glyph.width, baseY + glyph.height)
            gl.glTexCoord2f(uMin, vMax); gl.glVertex2f(cx, baseY + glyph.height)
            gl.glEnd()

            cx += glyph.width
            i++
        }
    }

    override fun getStringWidth(text: String): Int {
        var width = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\u00a7') { i += 2; continue }
            val code = ch.code
            if (code > 255) { i++; continue }
            width += glyphs[code]?.width ?: 0
            i++
        }
        return width
    }

    // ── Color component extraction helpers ──

    private fun shadowA(c: Int): Float = (((c ushr 24) and 0xFF) / 255f).coerceIn(0f, 1f)
    private fun shadowR(c: Int): Float = (((c shr 16) and 0xFF) / 255f).coerceIn(0f, 1f)
    private fun shadowG(c: Int): Float = (((c shr 8) and 0xFF) / 255f).coerceIn(0f, 1f)
    private fun shadowB(c: Int): Float = ((c and 0xFF) / 255f).coerceIn(0f, 1f)
}
