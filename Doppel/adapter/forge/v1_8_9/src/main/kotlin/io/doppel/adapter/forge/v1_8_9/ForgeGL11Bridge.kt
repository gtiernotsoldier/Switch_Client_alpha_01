package io.doppel.adapter.forge.v1_8_9

import io.doppel.adapter.common.render.GL11Bridge
import io.doppel.core.logging.CoreLogger

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
        gl11Class.getMethod("glDepthMask", Boolean::class.javaPrimitiveType)
    }
    private val glBlendFuncMethod by lazy {
        gl11Class.getMethod("glBlendFunc", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private val glColor4fMethod by lazy {
        gl11Class.getMethod("glColor4f", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private val glBeginMethod by lazy {
        gl11Class.getMethod("glBegin", Int::class.javaPrimitiveType)
    }
    private val glVertex2fMethod by lazy {
        gl11Class.getMethod("glVertex2f", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private val glEndMethod by lazy {
        gl11Class.getMethod("glEnd")
    }

    // ── Texture (SmoothFontRenderer) ──

    private val glGenTexturesMethod by lazy {
        gl11Class.getMethod("glGenTextures")
    }
    private val glBindTextureMethod by lazy {
        gl11Class.getMethod("glBindTexture", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private val glTexParameteriMethod by lazy {
        gl11Class.getMethod("glTexParameteri", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private val glTexEnvMethod by lazy {
        gl11Class.getMethod("glTexEnvi", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }
    private val glTexImage2DMethod by lazy {
        gl11Class.getMethod("glTexImage2D",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, java.nio.ByteBuffer::class.java)
    }
    private val glTexCoord2fMethod by lazy {
        gl11Class.getMethod("glTexCoord2f", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private val glScalefMethod by lazy {
        gl11Class.getMethod("glScalef", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }
    private val glTranslatefMethod by lazy {
        gl11Class.getMethod("glTranslatef", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
    }

    /** Track which methods have already logged errors to avoid spam. */
    private val loggedErrors = mutableSetOf<String>()

    private fun safeInvoke(methodName: String, method: java.lang.reflect.Method?, vararg args: Any?) {
        try {
            method?.invoke(null, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Reflection wraps the real GL exception — surface its message so
            // failures like LWJGL's "direct buffer required" are diagnosable.
            val cause = e.cause
            val detail = if (cause != null) "${cause.javaClass.simpleName}: ${cause.message}" else "null cause"
            if (loggedErrors.add(methodName)) {
                CoreLogger.error("[ForgeGL11Bridge] $methodName failed: $detail")
            }
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

    // ── Texture (SmoothFontRenderer) ──

    override fun glGenTextures(): Int {
        return try {
            (glGenTexturesMethod.invoke(null) as? Int) ?: 0
        } catch (e: Exception) {
            if (loggedErrors.add("glGenTextures")) {
                CoreLogger.error("[ForgeGL11Bridge] glGenTextures failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            0
        }
    }

    override fun glBindTexture(id: Int) {
        safeInvoke("glBindTexture", glBindTextureMethod, GL_TEXTURE_2D, id)
    }

    override fun glTexParameteri(target: Int, pname: Int, param: Int) {
        safeInvoke("glTexParameteri", glTexParameteriMethod, target, pname, param)
    }

    override fun glTexEnv(target: Int, pname: Int, param: Int) {
        safeInvoke("glTexEnvi", glTexEnvMethod, target, pname, param)
    }

    override fun glTexImage2DRGBA(width: Int, height: Int, pixels: java.nio.ByteBuffer) {
        safeInvoke("glTexImage2D", glTexImage2DMethod, GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    }

    // ── Font atlas upload via Minecraft's TextureUtil (reliable, nemui-style) ──

    private val textureUtilClass by lazy { Class.forName("net.minecraft.client.renderer.texture.TextureUtil") }
    private val tuGlGenTextures by lazy { textureUtilClass.getMethod("glGenTextures") }
    private val tuUploadImage by lazy {
        textureUtilClass.getMethod("uploadTextureImageAllocate",
            Int::class.javaPrimitiveType, java.awt.image.BufferedImage::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }

    override fun uploadFontTexture(image: java.awt.image.BufferedImage): Int {
        return try {
            val id = tuGlGenTextures.invoke(null) as? Int ?: return 0
            tuUploadImage.invoke(null, id, image, true, false)
            id
        } catch (e: Exception) {
            if (loggedErrors.add("uploadFontTexture")) {
                CoreLogger.error("[ForgeGL11Bridge] uploadFontTexture failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            0
        }
    }

    override fun glTexCoord2f(u: Float, v: Float) {
        safeInvoke("glTexCoord2f", glTexCoord2fMethod, u, v)
    }

    override fun glScalef(x: Float, y: Float, z: Float) {
        safeInvoke("glScalef", glScalefMethod, x, y, z)
    }

    override fun glTranslatef(x: Float, y: Float, z: Float) {
        safeInvoke("glTranslatef", glTranslatefMethod, x, y, z)
    }

    /** GL_TEXTURE_2D constant for [glBindTexture]. */
    private val GL_TEXTURE_2D = 0x0DE1
    private val GL_RGBA = 0x1908
    private val GL_UNSIGNED_BYTE = 0x1401
}
