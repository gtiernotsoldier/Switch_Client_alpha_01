package io.switchlite.adapter.common.render

/**
 * OpenGL constant values — identical across all LWJGL versions.
 * These are OpenGL spec values, not implementation-specific.
 * Defined here so the rendering layer never needs to import LWJGL directly.
 */
object GLConstants {
    const val GL_BLEND = 0x0BE2
    const val GL_DEPTH_TEST = 0x0B71
    const val GL_SRC_ALPHA = 0x0302
    const val GL_ONE_MINUS_SRC_ALPHA = 0x0303
    const val GL_TEXTURE_2D = 0x0DE1
    const val GL_QUADS = 0x0007
    const val GL_TRIANGLES = 0x0004
    const val GL_TRIANGLE_FAN = 0x0006
    const val GL_ALL_ATTRIB_BITS = 0x000FFFFF
    const val GL_PROJECTION = 0x1701
    const val GL_MODELVIEW = 0x1700
    const val GL_LIGHTING = 0x0B50

    // ── Texture / font rendering (SmoothFontRenderer) ──
    const val GL_RGBA = 0x1908
    const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_LINEAR = 0x2601
    const val GL_NEAREST = 0x2600
    const val GL_TEXTURE_MIN_FILTER = 0x2801
    const val GL_TEXTURE_MAG_FILTER = 0x2800
    const val GL_CLAMP_TO_EDGE = 0x812F
    const val GL_TEXTURE_ENV = 0x2300
    const val GL_TEXTURE_ENV_MODE = 0x2200
    const val GL_MODULATE = 0x2100

    // ── Alpha test (GuiScreen enables alpha discard — must disable for quads) ──
    const val GL_ALPHA_TEST = 0x0B20
}
