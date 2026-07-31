package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.render.GL11Bridge

/**
 * Forge 1.8.9 (LWJGL2) implementation of [GL11Bridge].
 *
 * Uses reflection to call LWJGL2's GL11 class because the agent's ClassLoader
 * cannot directly import LWJGL — it lives in Minecraft's ClassLoader.
 * All Method objects are lazily cached on first access.
 */
class ForgeGL11Bridge : GL11Bridge {

    private val gl11Class by lazy { Class.forName("org.lwjgl.opengl.GL11") }

    // ── Lazily cached Method objects ──

    private val glPushAttribMethod by lazy {
        gl11Class.getMethod("glPushAttrib", Int::class.javaPrimitiveType)
    }
    private val glPopAttribMethod by lazy {
        gl11Class.getMethod("glPopAttrib")
    }
    private val glMatrixModeMethod by lazy {
        gl11Class.getMethod("glMatrixMode", Int::class.javaPrimitiveType)
    }
    private val glPushMatrixMethod by lazy {
        gl11Class.getMethod("glPushMatrix")
    }
    private val glPopMatrixMethod by lazy {
        gl11Class.getMethod("glPopMatrix")
    }
    private val glLoadIdentityMethod by lazy {
        gl11Class.getMethod("glLoadIdentity")
    }
    private val glOrthoMethod by lazy {
        gl11Class.getMethod("glOrtho",
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
    }
    private val glEnableMethod by lazy {
        gl11Class.getMethod("glEnable", Int::class.javaPrimitiveType)
    }
    private val glDisableMethod by lazy {
        gl11Class.getMethod("glDisable", Int::class.javaPrimitiveType)
    }
    private val glDepthMaskMethod by lazy {
        gl11Class.getMethod("glDepthMask", Boolean::class.java)
    }
    private val glBlendFuncMethod by lazy {
        gl11Class.getMethod("glBlendFunc", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private val glColor4fMethod by lazy {
        gl11Class.getMethod("glColor4f", Float::class.java, Float::class.java, Float::class.java, Float::class.java)
    }
    private val glBeginMethod by lazy {
        gl11Class.getMethod("glBegin", Int::class.javaPrimitiveType)
    }
    private val glVertex2fMethod by lazy {
        gl11Class.getMethod("glVertex2f", Float::class.java, Float::class.java)
    }
    private val glEndMethod by lazy {
        gl11Class.getMethod("glEnd")
    }

    // ── GL11Bridge implementation ──

    override fun glPushAttrib(mask: Int) {
        glPushAttribMethod.invoke(null, mask)
    }

    override fun glPopAttrib() {
        glPopAttribMethod.invoke(null)
    }

    override fun glMatrixMode(mode: Int) {
        glMatrixModeMethod.invoke(null, mode)
    }

    override fun glPushMatrix() {
        glPushMatrixMethod.invoke(null)
    }

    override fun glPopMatrix() {
        glPopMatrixMethod.invoke(null)
    }

    override fun glLoadIdentity() {
        glLoadIdentityMethod.invoke(null)
    }

    override fun glOrtho(left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double) {
        glOrthoMethod.invoke(null, left, right, bottom, top, near, far)
    }

    override fun glEnable(cap: Int) {
        glEnableMethod.invoke(null, cap)
    }

    override fun glDisable(cap: Int) {
        glDisableMethod.invoke(null, cap)
    }

    override fun glDepthMask(flag: Boolean) {
        glDepthMaskMethod.invoke(null, flag)
    }

    override fun glBlendFunc(sfactor: Int, dfactor: Int) {
        glBlendFuncMethod.invoke(null, sfactor, dfactor)
    }

    override fun glColor4f(red: Float, green: Float, blue: Float, alpha: Float) {
        glColor4fMethod.invoke(null, red, green, blue, alpha)
    }

    override fun glBegin(mode: Int) {
        glBeginMethod.invoke(null, mode)
    }

    override fun glVertex2f(x: Float, y: Float) {
        glVertex2fMethod.invoke(null, x, y)
    }

    override fun glEnd() {
        glEndMethod.invoke(null)
    }
}
