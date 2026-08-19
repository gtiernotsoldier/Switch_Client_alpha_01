package io.switchlite.adapter.common.ui

import java.awt.Color

/**
 * Unified color palette — SwitchLite's own dark theme.
 * All colors are ARGB ints (alpha in the top byte).
 *
 * Pure Kotlin + JDK (java.awt.Color for HSB math) — no Minecraft dependency.
 */
object Theme {

    // ── Base palette ──
    // SwitchLite's own dark theme — clean, modern, high-contrast.
    // Panels are deep neutral dark; text is near-white for readability;
    // a single cool accent (cyan) marks active/positive state; red/gold
    // reserved for errors/warnings. No muddled gray-on-gray.

    /** Panel background: deep near-black, slightly translucent. */
    const val PANEL_BG: Int = 0xE6141418.toInt()

    /** Fully-opaque panel background (for full-screen GUI backdrop). */
    const val PANEL_BG_FULL: Int = 0xFF101014.toInt()

    /** HUD card background: more transparent so the game stays visible. */
    const val HUD_BG: Int = 0x66000000.toInt()

    /** Hover highlight (subtle white overlay). */
    const val HOVER: Int = 0x14FFFFFF

    /** Primary text: near-white for readability. */
    const val TEXT: Int = 0xFFF0F0F0.toInt()

    /** Dim / disabled text: muted gray. */
    const val TEXT_DIM: Int = 0xFF707070.toInt()

    /** Accent cyan — active / enabled / positive (modern, not garish green). */
    const val ACCENT: Int = 0xFF4FC3F7.toInt()

    /** Error red. */
    const val ERROR: Int = 0xFFFF5252.toInt()

    /** Warning gold. */
    const val WARN: Int = 0xFFFFB300.toInt()

    /** ClickGUI title accent (same cyan family, slightly brighter). */
    const val TITLE: Int = 0xFF81D4FA.toInt()

    /** Panel border (subtle). */
    const val BORDER: Int = 0x22FFFFFF

    /** Slider track (unfilled). */
    const val TRACK: Int = 0x33FFFFFF

    /** Slider knob. */
    const val KNOB: Int = 0xFFE0E0E0.toInt()

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
