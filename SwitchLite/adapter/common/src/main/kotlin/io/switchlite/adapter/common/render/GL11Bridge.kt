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
    fun glEnd()
}
