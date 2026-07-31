package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.render.GL11Bridge
import io.switchlite.core.logging.CoreLogger

/**
 * Forge 1.8.9 (LWJGL2) implementation of [GL11Bridge].
 *
 * Uses reflection to call LWJGL2's GL11 class because the agent's ClassLoader
 * cannot directly import LWJGL — it lives in Minecraft's ClassLoader.
 * All Method objects are lazily cached on first access.
 *
 * Every method call is wrapped in try-catch so that a single reflection failure
 * does not crash the render pipeline or corrupt MC's GL state stack.
 * If a method fails, it is logged once and silently skipped.
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

    /** Track which methods have already logged errors to avoid spam. */
    private val loggedErrors = mutableSetOf<String>()

    private fun safeInvoke(methodName: String, method: java.lang.reflect.Method?, vararg args: Any?) {
        try {
            method?.invoke(null, *args)
        } catch (e: Exception) {
            if (loggedErrors.add(methodName)) {
                CoreLogger.error("[ForgeGL11Bridge] $methodName failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ── GL11Bridge implementation ──

    override fun glPushAttrib(mask: Int) {
        safeInvoke("glPushAttrib", glPushAttribMethod, mask)
    }

    override fun glPopAttrib() {
        safeInvoke("glPopAttrib", glPopAttribMethod)
    }

    override fun glMatrixMode(mode: Int) {
        safeInvoke("glMatrixMode", glMatrixModeMethod, mode)
    }

    override fun glPushMatrix() {
        safeInvoke("glPushMatrix", glPushMatrixMethod)
    }

    override fun glPopMatrix() {
        safeInvoke("glPopMatrix", glPopMatrixMethod)
    }

    override fun glLoadIdentity() {
        safeInvoke("glLoadIdentity", glLoadIdentityMethod)
    }

    override fun glOrtho(left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double) {
        safeInvoke("glOrtho", glOrthoMethod, left, right, bottom, top, near, far)
    }

    override fun glEnable(cap: Int) {
        safeInvoke("glEnable", glEnableMethod, cap)
    }

    override fun glDisable(cap: Int) {
        safeInvoke("glDisable", glDisableMethod, cap)
    }

    override fun glDepthMask(flag: Boolean) {
        safeInvoke("glDepthMask", glDepthMaskMethod, flag)
    }

    override fun glBlendFunc(sfactor: Int, dfactor: Int) {
        safeInvoke("glBlendFunc", glBlendFuncMethod, sfactor, dfactor)
    }

    override fun glColor4f(red: Float, green: Float, blue: Float, alpha: Float) {
        safeInvoke("glColor4f", glColor4fMethod, red, green, blue, alpha)
    }

    override fun glBegin(mode: Int) {
        safeInvoke("glBegin", glBeginMethod, mode)
    }

    override fun glVertex2f(x: Float, y: Float) {
        safeInvoke("glVertex2f", glVertex2fMethod, x, y)
    }

    override fun glEnd() {
        safeInvoke("glEnd", glEndMethod)
    }
}
