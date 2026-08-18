package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.ui.Theme
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * HUD — on-screen module status card (Nemui-style).
 *
 * Builds the enabled-module list every tick, then exposes layout data for
 * OverlayRenderer to draw as a draggable card. Sorting, coloring and
 * brightness are configurable via the ClickGUI settings panel
 * (they register as options under "HUD.*").
 *
 * Dragging is only active while the ClickGUI is open (avoids stealing
 * clicks from normal gameplay). Positions are clamped to the screen.
 */
object HUD : Module("HUD", Category.RENDER) {

    /**
     * A single HUD line entry to be rendered by the adapter.
     */
    data class HUDEntry(
        val name: String,
        /** True if this entry should be rendered in red (active indicator). */
        val isRed: Boolean
    )

    /** Current HUD entries — rebuilt every tick when enabled. */
    @Volatile
    var hudEntries: List<HUDEntry> = emptyList()
        private set

    // ── Card position (draggable while GUI is open) ──

    var posX: Int = 4
        private set
    var posY: Int = 4
        private set

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var wasLeftDown = false

    // ── Configurable display options (ClickGUI settings panel) ──

    var sortMode by choices("Sort", arrayOf("None", "Alphabetical", "Length", "Category"))
    var colorMode by choices("Color", arrayOf("Static", "RandomRainbow", "FadeRainbow"))
    var brightness by choices("Brightness", arrayOf("Darker", "Dark", "Normal", "Bright", "Brighter"))
    var reversed by boolean("Reversed", false)

    // ── Data (every tick) ──

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) onTick()
    }

    private fun onTick() {
        val redEnabled = EventBridge.isRedIndicatorEnabled
        hudEntries = ModuleRegistry.getEnabled()
            .filter { it.visible }
            .map { module ->
                HUDEntry(
                    name = module.name,
                    isRed = redEnabled
                        && module.showRedIndicator
                        && module.category !in Module.silentCategories
                )
            }
        // Also set the simple text line for backward compat (adapter may use either)
        val names = hudEntries.joinToString(" | ") { it.name }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }

    // ── Layout helpers for OverlayRenderer ──

    /** Sorted entries according to [sortMode] + [reversed]. */
    fun sortedEntries(): List<HUDEntry> {
        val entries = hudEntries.toMutableList()
        when (sortMode) {
            "Alphabetical" -> entries.sortBy { it.name }
            "Length" -> entries.sortBy { it.name.length }
            "Category" -> entries.sortBy { categoryOrder(it.name) }
        }
        if (reversed) entries.reverse()
        return entries
    }

    private fun categoryOrder(moduleName: String): Int {
        return ModuleRegistry.getAll()
            .firstOrNull { it.name == moduleName }
            ?.category?.ordinal ?: Int.MAX_VALUE
    }

    /** Per-line color according to [colorMode] (red indicator wins). */
    fun entryColor(index: Int, entry: HUDEntry): Int {
        if (entry.isRed) return Theme.ERROR
        return when (colorMode) {
            "RandomRainbow" -> Theme.rainbow(entry.name.hashCode())
            "FadeRainbow" -> Theme.rainbow(index * 25)
            else -> Theme.TEXT
        }
    }

    /** Brightness multiplier from the [brightness] option. */
    fun brightnessFactor(): Float {
        return try {
            Theme.Brightness.valueOf(brightness.uppercase()).factor
        } catch (e: Exception) {
            1.0f
        }
    }

    // ── Drag handling (GUI-open only) ──

    /**
     * Handle mouse input for HUD dragging. Only active while the ClickGUI
     * is open; [cardWidth]/[cardHeight] describe the card's rendered size.
     */
    fun handleMouseInput(
        x: Int,
        y: Int,
        leftDown: Boolean,
        scaledWidth: Int,
        scaledHeight: Int,
        cardWidth: Int,
        cardHeight: Int
    ) {
        if (!ClickGUI.isOpen()) {
            wasLeftDown = false
            dragging = false
            return
        }
        val clicked = leftDown && !wasLeftDown
        val released = !leftDown && wasLeftDown
        wasLeftDown = leftDown

        if (leftDown && clicked) {
            if (x >= posX && x < posX + cardWidth && y >= posY && y < posY + cardHeight) {
                dragging = true
                dragOffsetX = x - posX
                dragOffsetY = y - posY
                return
            }
        }
        if (leftDown && dragging) {
            posX = (x - dragOffsetX).coerceIn(0, (scaledWidth - cardWidth).coerceAtLeast(0))
            posY = (y - dragOffsetY).coerceIn(0, (scaledHeight - cardHeight).coerceAtLeast(0))
        }
        if (released) {
            dragging = false
        }
    }

    // ── Lifecycle ──

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.hudTextLine = ""
        hudEntries = emptyList()
        dragging = false
        wasLeftDown = false
    }
}
