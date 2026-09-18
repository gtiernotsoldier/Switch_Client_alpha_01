package io.doppel.adapter.common.hud

import io.doppel.adapter.common.render.GL11Bridge
import io.doppel.adapter.common.render.GLConstants
import io.doppel.adapter.common.render.SmoothFontRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Font

/**
 * SmoothFontRenderer atlas lifecycle with a fake GL bridge — validates the
 * rasterization, measurement and draw plumbing on the plain JVM (no GL).
 */
class SmoothFontAtlasTest {

    /** Minimal recording fake: textures "upload" fine, GL calls are recorded. */
    private open class FakeGL : GL11Bridge {
        var uploads = 0
        var binds = 0
        var nextId = 1
        val enabledCaps = mutableListOf<Int>()
        val disabledCaps = mutableListOf<Int>()
        var lastBound = -1
        val deletedTex = mutableListOf<Int>()
        override fun glGenTextures(): Int = nextId++
        override fun glBindTexture(id: Int) { binds++; lastBound = id }
        override fun glTexParameteri(target: Int, pname: Int, param: Int) {}
        override fun glEnable(cap: Int) { enabledCaps.add(cap) }
        override fun glDisable(cap: Int) { disabledCaps.add(cap) }
        override fun glDeleteTextures(id: Int) { deletedTex.add(id) }
        override fun uploadFontTexture(image: java.awt.image.BufferedImage): Int {
            uploads++
            val id = nextId++
            lastBound = id // mirror the real bridge: upload binds the atlas
            return id
        }
        override fun uploadFontTextureInto(id: Int, image: java.awt.image.BufferedImage): Boolean {
            uploads++
            lastBound = id // mirror the real bridge: upload binds the atlas
            return true
        }
        override fun glPushAttrib(mask: Int) {}
        override fun glPopAttrib() {}
        override fun glMatrixMode(mode: Int) {}
        override fun glPushMatrix() {}
        override fun glPopMatrix() {}
        override fun glLoadIdentity() {}
        override fun glOrtho(left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double) {}
        override fun glDepthMask(flag: Boolean) {}
        override fun glBlendFunc(sfactor: Int, dfactor: Int) {}
        override fun glColor4f(red: Float, green: Float, blue: Float, alpha: Float) {}
        override fun glBegin(mode: Int) {}
        override fun glVertex2f(x: Float, y: Float) {}
        override fun glEnd() {}
        override fun glTexEnv(target: Int, pname: Int, param: Int) {}
        override fun glTexImage2DRGBA(width: Int, height: Int, pixels: java.nio.ByteBuffer) {}
        override fun glTexCoord2f(u: Float, v: Float) {}
        override fun glScalef(x: Float, y: Float, z: Float) {}
        override fun glTranslatef(x: Float, y: Float, z: Float) {}
    }

    private fun newFont(gl: GL11Bridge) =
        SmoothFontRenderer(Font(Font.SANS_SERIF, Font.PLAIN, 18), gl, guiSize = 9f, renderScale = 2)

    @Test
    fun `atlas prepares, measures widths and reports sane metrics`() {
        val gl = FakeGL()
        val f = newFont(gl)
        assertTrue(f.prepare(), "first prepare() should upload the atlas")
        assertTrue(f.ready)
        assertEquals(1, gl.uploads)
        assertTrue(f.fontHeight in 4..40, "fontHeight=${f.fontHeight} must be derived, sane")

        val wReach = f.getStringWidth("Reach")
        val wKeep = f.getStringWidth("KeepSprint")
        val wAuto = f.getStringWidth("AutoClicker")
        assertTrue(wReach > 0)
        assertTrue(wAuto > wKeep, "AutoClicker wider than KeepSprint")
        assertTrue(wKeep > wReach, "KeepSprint wider than Reach")
    }

    @Test
    fun `width measure matches draw return value`() {
        val gl = FakeGL()
        val f = newFont(gl)
        f.prepare()
        val text = "AimAssist"
        assertEquals(f.getStringWidth(text), f.drawStringWithShadow(text, 0, 0, 0xFFFFFFFF.toInt()))
    }

    @Test
    fun `failed upload retries instead of sticking forever`() {
        val gl = object : FakeGL() {
            override fun uploadFontTexture(image: java.awt.image.BufferedImage): Int = 0
        }
        val f = newFont(gl)
        // 10 capped retries: prepare keeps reporting false but never throws.
        repeat(12) { assertTrue(!f.prepare()) }
        f.getStringWidth("WTap") // measurement works regardless of texture state
    }

    @Test
    fun `mirrored glyph returns its cell width`() {
        val gl = FakeGL()
        val f = newFont(gl)
        f.prepare()
        val w = f.drawCharMirrored('D', 0f, 0f, 0xFF22D3EE.toInt())
        assertEquals(f.getStringWidth("D").toFloat(), w, 0.01f)
    }

    @Test
    fun `drawing restores alpha test and never re-enables depth (state hygiene)`() {
        val gl = FakeGL()
        val f = newFont(gl)
        f.prepare()
        val before = gl.enabledCaps.size
        f.drawStringWithShadow("AutoClicker", 0, 0, 0xFFFFFFFF.toInt())
        // v3.3 contract: the renderer restores what IT touched — alpha test
        // back ON, atlas UNBOUND (texture bindings are not in glPushAttrib),
        // and depth test must NEVER be enabled mid-frame (the old hardcoded
        // restore broke untextured quads drawn after text).
        assertTrue(gl.enabledCaps.contains(GLConstants.GL_ALPHA_TEST), "alpha test must be restored")
        assertFalse(gl.enabledCaps.contains(GLConstants.GL_DEPTH_TEST), "depth test must not be re-enabled")
        assertEquals(0, gl.lastBound, "atlas binding must be released after drawing")
        assertTrue(gl.enabledCaps.size > before || gl.enabledCaps.isNotEmpty())
    }

    @Test
    fun `release deletes the atlas texture and clears readiness`() {
        val gl = FakeGL()
        val f = newFont(gl)
        f.prepare()
        val tex = gl.lastBound
        assertTrue(f.ready)
        f.release()
        assertEquals(listOf(tex), gl.deletedTex, "release must delete the atlas texture")
        assertFalse(f.ready, "readiness must clear after release")
        // A released font can prepare() again (fresh upload) — the rebuild path.
        assertTrue(f.prepare())
    }
}
