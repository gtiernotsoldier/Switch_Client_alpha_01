package io.switchlite.adapter.common.ui

import java.awt.Color

/**
 * Unified color palette — Nemui-inspired dark theme.
 * All colors are ARGB ints (alpha in the top byte).
 *
 * Pure Kotlin + JDK (java.awt.Color for HSB math) — no Minecraft dependency.
 */
object Theme {

    // ── Base palette ──

    /** Panel background: near-black with heavy opacity (Nemui 0xE6101014). */
    const val PANEL_BG: Int = 0xE6101014.toInt()

    /** HUD card background: more transparent so the game stays visible. */
    const val HUD_BG: Int = 0x99000000.toInt()

    /** Hover highlight. */
    const val HOVER: Int = 0x2AFFFFFF

    /** Primary text. */
    const val TEXT: Int = 0xFFFFFFFF.toInt()

    /** Dim / disabled text. */
    const val TEXT_DIM: Int = 0xFFAAAAAA.toInt()

    /** Accent green (enabled / positive). */
    const val ACCENT: Int = 0xFF55FF55.toInt()

    /** Error red. */
    const val ERROR: Int = 0xFFFF5555.toInt()

    /** Warning gold. */
    const val WARN: Int = 0xFFFFAA00.toInt()

    /** ClickGUI title gold. */
    const val TITLE: Int = 0xFFFF55

    /** Panel border (subtle). */
    const val BORDER: Int = 0x33FFFFFF

    /** Slider track (unfilled). */
    const val TRACK: Int = 0x55FFFFFF

    /** Slider knob. */
    const val KNOB: Int = 0xFFFFFFFF.toInt()

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
