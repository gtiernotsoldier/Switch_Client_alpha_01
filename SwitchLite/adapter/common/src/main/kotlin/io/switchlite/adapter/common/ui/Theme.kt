package io.switchlite.adapter.common.ui

import java.awt.Color

/**
 * Unified color palette — SwitchLite's own dark theme.
 * All colors are ARGB ints (alpha in the top byte).
 *
 * Pure Kotlin + JDK (java.awt.Color for HSB math) — no Minecraft dependency.
 */
object Theme {

    // ── Aurora visual system ──
    // Deep gradient background, translucent cards, near-white text, and a
    // per-category accent color (HSL-hue rotated) — each category card gets
    // its own accent/glow instead of a single global color.

    /** Full-screen GUI backdrop — Aurora deep gradient base (most opaque). */
    const val PANEL_BG_FULL: Int = 0xFF0A0A0F.toInt()

    /** Card background — translucent deep panel (Aurora rgba(26,26,36,.85)). */
    const val PANEL_BG: Int = 0xD91A1A24.toInt()

    /** HUD card background. */
    const val HUD_BG: Int = 0xB3181820.toInt()

    /** Hover highlight (subtle white overlay). */
    const val HOVER: Int = 0x12FFFFFF

    /** Primary text: white. */
    const val TEXT: Int = 0xFFFFFFFF.toInt()

    /** Secondary text: 60% white. */
    const val TEXT_DIM: Int = 0x99FFFFFF.toInt()

    /** Tertiary text: 35% white. */
    const val TEXT_FAINT: Int = 0x59FFFFFF.toInt()

    /** Card border (13% white). */
    const val BORDER: Int = 0x21FFFFFF

    /** Slider track (unfilled). */
    const val TRACK: Int = 0x21FFFFFF

    /** Error red. */
    const val ERROR: Int = 0xFFFF5252.toInt()

    /** Warning gold. */
    const val WARN: Int = 0xFFFFB300.toInt()

    /** Legacy single accent (kept for callers that don't use per-category). */
    const val ACCENT: Int = 0xFFFF6A00.toInt()

    // ── Per-category Aurora accents (HSL-hue rotated) ──

    /**
     * Accent color for a category, matching Aurora's per-card hue rotation.
     * Base hue 0.8 (warm orange), categories offset their hue.
     */
    fun accentFor(categoryOrdinal: Int): Int {
        val offset = when (categoryOrdinal) {
            0 -> 0.00f  // RENDER  — warm orange
            1 -> 0.33f  // COMBAT  — gold
            2 -> 0.66f  // MOVEMENT— mint
            3 -> 0.15f  // PLAYER  — pink/purple
            4 -> 0.50f  // WORLD   — yellow-green
            else -> 0.0f
        }
        val hue = (0.8f + offset) % 1.0f
        return Color.HSBtoRGB(hue, 0.9f, 0.6f) or 0xFF000000.toInt()
    }

    /** Soft accent (35% alpha) for borders/glow on category cards. */
    fun accentSoft(categoryOrdinal: Int): Int {
        val base = accentFor(categoryOrdinal)
        val r = (base shr 16) and 0xFF
        val g = (base shr 8) and 0xFF
        val b = base and 0xFF
        return (0x59 shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ── Rainbow ──

    /**
     * HSB rainbow color, hue rotating over time.
     * @param phase per-row offset (e.g. row index * 25) to desync rows
     * @param speed rotation speed in hue-degrees per second
     */
    fun rainbow(phase: Int, speed: Float = 90f): Int {
        val ms = System.currentTimeMillis()
        val hue = ((ms / 1000f) * speed + phase) % 360f / 360f
        return Color.HSBtoRGB(hue, 0.8f, 1f)
    }

    // ── Shade / alpha helpers ──

    /** Brighten (factor > 1) or darken (factor < 1) an ARGB color. */
    fun shade(argb: Int, factor: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (((argb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Scale the alpha channel of an ARGB color. */
    fun withAlpha(argb: Int, mul: Float): Int {
        val a = (((argb ushr 24) and 0xFF) * mul).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** Named brightness presets for the HUD. */
    enum class Brightness(val factor: Float) {
        DARKER(0.55f), DARK(0.75f), NORMAL(1.0f), BRIGHT(1.25f), BRIGHTER(1.5f)
    }
}
