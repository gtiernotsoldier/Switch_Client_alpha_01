package io.doppel.adapter.common.module

/**
 * OPTIONAL customization for a module's in-game HUD line.
 *
 * By default the HUD auto-shows a module's first numeric option (e.g. STap/WTap
 * display their first number); toggle-only modules show just their name. Only
 * implement this to override with a custom value (e.g. Velocity's "Legit 50/50%"
 * or AutoClicker's "10-20 CPS").
 *
 * The HUD is event-driven (collect on enable / config change / toggle), so
 * [hudValue] should be cheap.
 */
interface HudLineProvider {

    /**
     * The value text shown next to the module name, e.g. "20 CPS" / "Adaptive".
     * Return "" to fall back to the auto-derived first-numeric-option value.
     */
    fun hudValue(): String = ""

    /**
     * True to draw the value in the highlight (warm orange) color — use for
     * numeric/live values (CPS, ms, distance). False = plain white value.
     */
    fun hudHighlight(): Boolean = false
}
