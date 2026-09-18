package io.doppel.adapter.forge.v1_8_9

import io.doppel.adapter.common.render.GL11Bridge
import io.doppel.adapter.common.render.GLConstants
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

    private val glCopyTexSubImage2DMethod by lazy {
        gl11Class.getMethod("glCopyTexSubImage2D",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    // ── State queries / cleanup (v3.3) ──

    private val glGetErrorMethod by lazy {
        gl11Class.getMethod("glGetError")
    }
    private val glGetIntegerMethod by lazy {
        gl11Class.getMethod("glGetInteger", Int::class.javaPrimitiveType, java.nio.IntBuffer::class.java)
    }
    private val glDeleteTexturesMethod by lazy {
        gl11Class.getMethod("glDeleteTextures", Int::class.javaPrimitiveType)
    }

    /** Reusable direct buffer for single-value glGetInteger queries. */
    private val queryBuffer by lazy {
        java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer()
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

    // ── Font atlas upload (v3 reliability ladder) ──
    //
    // The first SmoothFontRenderer attempt was rolled back because its upload
    // path "did not reliably render". Root causes addressed here:
    //   1. manual glTexImage2D with a HEAP ByteBuffer → LWJGL throws
    //      IllegalArgumentException("direct buffer required") → nothing draws;
    //   2. no retry/re-assert → a resource reload (F3+T) deletes raw GL textures
    //      and the font silently vanishes.
    //
    // Ladder: PRIMARY = manual upload with a DIRECT ByteBuffer and explicit
    // ARGB→RGBA byte order (fully ours, no MC classes); FALLBACK = MC's
    // TextureUtil.uploadTextureImageAllocate (method-name verified, with a
    // one-shot diagnostic dump of TextureUtil's declared methods if missing).

    private val textureUtilClass by lazy { Class.forName("net.minecraft.client.renderer.texture.TextureUtil") }
    private val tuUploadImage by lazy {
        textureUtilClass.methods.firstOrNull {
            it.name == "uploadTextureImageAllocate" &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == java.awt.image.BufferedImage::class.java
        }
    }

    override fun uploadFontTexture(image: java.awt.image.BufferedImage): Int {
        val id = glGenTextures()
        if (id <= 0) return 0
        return if (uploadFontTextureInto(id, image)) id else 0
    }

    override fun uploadFontTextureInto(id: Int, image: java.awt.image.BufferedImage): Boolean {
        // PRIMARY: manual, fully-controlled path. AWT TYPE_INT_ARGB is
        // NON-premultiplied ARGB ints; push bytes in RGBA order into a DIRECT
        // native-order buffer (LWJGL rejects heap buffers).
        try {
            val w = image.width
            val h = image.height
            val argb = IntArray(w * h)
            image.getRGB(0, 0, w, h, argb, 0, w)
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            for (p in argb) {
                buf.put(((p shr 16) and 0xFF).toByte())  // R
                buf.put(((p shr 8) and 0xFF).toByte())   // G
                buf.put((p and 0xFF).toByte())           // B
                buf.put(((p ushr 24) and 0xFF).toByte()) // A
            }
            buf.flip()
            glBindTexture(id)
            glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MIN_FILTER, GLConstants.GL_LINEAR)
            glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MAG_FILTER, GLConstants.GL_LINEAR)
            glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_WRAP_S, GLConstants.GL_CLAMP_TO_EDGE)
            glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_WRAP_T, GLConstants.GL_CLAMP_TO_EDGE)
            // Drain any stale error from earlier in the frame, then upload and
            // VERIFY: safeInvoke swallows GL-level failures, so without this
            // check an unuploaded atlas still returned "true" — ready==true,
            // yet sampling garbage/undefined storage (invisible text).
            while (glGetError() != 0) { /* clear pending flag */ }
            glTexImage2DRGBA(w, h, buf)
            val err = glGetError()
            if (err != 0) {
                if (loggedErrors.add("uploadFontTexture.glerror")) {
                    CoreLogger.error("[ForgeGL11Bridge] manual atlas upload left GL error 0x${Integer.toHexString(err)} — falling back to TextureUtil")
                }
                throw IllegalStateException("glTexImage2D error 0x${Integer.toHexString(err)}")
            }
            // Leave NO binding behind: the atlas texture must not leak into
            // whatever MC draws next (texture binding is not in glPushAttrib).
            glBindTexture(0)
            return true
        } catch (e: Exception) {
            if (loggedErrors.add("uploadFontTexture.manual")) {
                CoreLogger.error("[ForgeGL11Bridge] manual atlas upload failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        // FALLBACK: MC TextureUtil (blur=true → GL_LINEAR, clamp=false).
        return try {
            val m = tuUploadImage
            if (m == null) {
                if (loggedErrors.add("uploadFontTexture.tu.missing")) {
                    CoreLogger.error(
                        "[ForgeGL11Bridge] TextureUtil.uploadTextureImageAllocate(Int,BufferedImage,Z,Z) not found; methods=" +
                            textureUtilClass.methods.map { it.name }.filter { it.startsWith("upload") || it == "glGenTextures" }.sorted()
                    )
                }
                false
            } else {
                m.invoke(null, id, image, true, false)
                // TextureUtil leaves the atlas bound — unbind (no leaks policy).
                glBindTexture(0)
                true
            }
        } catch (e: Exception) {
            if (loggedErrors.add("uploadFontTexture.tu")) {
                CoreLogger.error("[ForgeGL11Bridge] TextureUtil upload failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    override fun glCopyTexSubImage2D(x: Int, y: Int, width: Int, height: Int): Boolean {
        return try {
            glCopyTexSubImage2DMethod.invoke(
                null,
                GLConstants.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height
            )
            true
        } catch (e: Exception) {
            if (loggedErrors.add("glCopyTexSubImage2D")) {
                CoreLogger.error("[ForgeGL11Bridge] glCopyTexSubImage2D failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
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

    // ── State queries / cleanup (v3.3) ──

    override fun glGetError(): Int {
        return try {
            (glGetErrorMethod.invoke(null) as? Int) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun glGetInteger(pname: Int): Int {
        return try {
            queryBuffer.clear()
            glGetIntegerMethod.invoke(null, pname, queryBuffer)
            queryBuffer.get(0)
        } catch (e: Exception) {
            0
        }
    }

    override fun glDeleteTextures(id: Int) {
        safeInvoke("glDeleteTextures", glDeleteTexturesMethod, id)
    }

    /** GL_TEXTURE_2D constant for [glBindTexture]. */
    private val GL_TEXTURE_2D = 0x0DE1
    private val GL_RGBA = 0x1908
    private val GL_UNSIGNED_BYTE = 0x1401
}
