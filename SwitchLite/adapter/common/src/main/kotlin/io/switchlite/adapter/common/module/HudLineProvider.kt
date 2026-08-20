package io.switchlite.adapter.common.module

/**
 * Declares what a module wants to show on the in-game HUD while it is enabled.
 *
 * The HUD is event-driven, not per-tick: it collects lines once when a module
 * is enabled (or its config changes), so [hudValue] should be cheap to compute.
 * Only modules implementing this interface appear on the HUD. Per-module
 * visibility (hide from HUD) lives on [Module.hudHidden].
 *
 * Example: AutoClicker returns "20 CPS" (highlight = true, numeric value);
 * a toggle-only module like Sprint returns "" (name only, no value).
 */
interface HudLineProvider {

    /**
     * The value text shown next to the module name, e.g. "20 CPS" / "Adaptive".
     * Return "" to show only the module name (no value column).
     */
    fun hudValue(): String = ""

    /**
     * True to draw the value in the highlight (warm orange) color — use for
     * numeric/live values (CPS, ms, distance). False = plain white value.
     */
    fun hudHighlight(): Boolean = false
}
