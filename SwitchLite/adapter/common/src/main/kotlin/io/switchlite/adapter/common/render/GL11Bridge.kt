package io.switchlite.adapter.common.render

/**
 * Abstraction over OpenGL 1.1 immediate-mode calls.
 *
 * Each version adapter provides its own implementation that handles
 * the ClassLoader boundary (agent CL vs Minecraft CL) internally.
 * The consumer (OverlayRenderer) never knows or cares whether
 * the underlying GL11 is LWJGL2 or LWJGL3 — it just calls the interface.
 *
 * Design principle: "人家给你东西了为什么要去反射" —
 * use the language's abstraction mechanism (interface), not reflection in shared code.
 */
interface GL11Bridge {

    // ── State save / restore ──

    fun glPushAttrib(mask: Int)
    fun glPopAttrib()

    fun glMatrixMode(mode: Int)
    fun glPushMatrix()
    fun glPopMatrix()
    fun glLoadIdentity()

    // ── Projection ──

    fun glOrtho(
        left: Double, right: Double,
        bottom: Double, top: Double,
        near: Double, far: Double
    )

    // ── Capability toggles ──

    fun glEnable(cap: Int)
    fun glDisable(cap: Int)

    // ── Depth / blend ──

    fun glDepthMask(flag: Boolean)
    fun glBlendFunc(sfactor: Int, dfactor: Int)

    // ── Drawing ──

    fun glColor4f(red: Float, green: Float, blue: Float, alpha: Float)
    fun glBegin(mode: Int)
    fun glVertex2f(x: Float, y: Float)
    fun glVertex3f(x: Float, y: Float, z: Float)
    fun glEnd()

    /** Set the OpenGL line width (for world-space wireframe overlays; thin by default). */
    fun glLineWidth(width: Float)

    /** Set GL_TEXTURE_ENV mode (e.g. GL_MODULATE) so glColor4f tints textured quads. */
    fun glTexEnv(target: Int, pname: Int, param: Int)

    // ── Texture (SmoothFontRenderer) ──

    /** Generate a texture name (GL11.glGenTextures). */
    fun glGenTextures(): Int

    /** Bind a 2D texture (GL11.glBindTexture(GL_TEXTURE_2D, id)). */
    fun glBindTexture(id: Int)

    /** Set a texture parameter (e.g. GL_TEXTURE_MIN_FILTER / GL_LINEAR). */
    fun glTexParameteri(target: Int, pname: Int, param: Int)

    /**
     * Upload RGBA pixel data into the currently bound texture.
     * [pixels] is a ByteBuffer of width*height*4 bytes in R,G,B,A order.
     */
    fun glTexImage2DRGBA(width: Int, height: Int, pixels: java.nio.ByteBuffer)

    /**
     * Reliably upload a glyph atlas image as a GL texture (nemui-style, via the
     * platform's Minecraft texture path). Returns a valid texture id, or 0 on
     * failure. Implementations should use MC's TextureUtil/DynamicTexture so the
     * upload always works (manual glTexImage2D has proven unreliable here).
     */
    fun uploadFontTexture(image: java.awt.image.BufferedImage): Int

    /** Set the 2D texture coordinate for the next vertex. */
    fun glTexCoord2f(u: Float, v: Float)

    /** Scale the current matrix by (x, y, z). */
    fun glScalef(x: Float, y: Float, z: Float)

    /** Translate the current matrix by (x, y, z). */
    fun glTranslatef(x: Float, y: Float, z: Float)
}
