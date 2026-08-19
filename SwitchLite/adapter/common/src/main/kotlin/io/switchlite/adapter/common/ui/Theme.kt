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
    //
    // Aligned with Nemui's Dark theme (me.sleepyfish.nemui ColorUtils):
    //   backgroundColor  = #131313 @ a160
    //   fontColor        = #A9A9A9 (gray 169)  — enabled text
    //   disabled         = fontColor .darker().darker() — nearly black
    //   full background  = #131313 @ a255
    //
    // Nemui's look is deliberately monochrome: gray text on near-black
    // panels, with a handful of saturated accents reserved for state
    // indicators (version icon / enabled toggle) and notifications.

    /** Panel background: Nemui backgroundColor (#131313 @ a160). */
    const val PANEL_BG: Int = 0xA0131313.toInt()

    /** Fully-opaque panel background (Nemui backgroundColorFull #131313). */
    const val PANEL_BG_FULL: Int = 0xFF131313.toInt()

    /** HUD card background: more transparent so the game stays visible. */
    const val HUD_BG: Int = 0x73000000.toInt()

    /** Hover highlight (subtle, Nemui has no colored hover — just dimming). */
    const val HOVER: Int = 0x14FFFFFF

    /** Primary text: Nemui fontColor (#A9A9A9) — enabled / default. */
    const val TEXT: Int = 0xFFA9A9A9.toInt()

    /** Disabled text: Nemui fontColor.darker().darker() (nearly black). */
    const val TEXT_DIM: Int = 0xFF4A4A4A.toInt()

    /** Accent green — reserved for enabled state / positive (Nemui lightGreenNormal #50FF50). */
    const val ACCENT: Int = 0xFF50FF50.toInt()

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
