package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.HudLineProvider
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.ConfigManager
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * HUD — transparent in-game module status list (SwitchLite style).
 *
 * Visual: plain text lines, no card background — module name + optional value.
 * A line turns RED while its module is enabled (quick debug visibility); values
 * that are numeric (CPS/ms) can be highlighted in warm orange.
 *
 * Refresh is event-driven, NOT per tick:
 *  - collected once when the HUD enables,
 *  - re-collected when any module option changes (ConfigManager.onModuleDirty),
 *  - re-collected when a module toggles on/off (tick diff is a cheap safety net).
 * Position (left/right) is configured from the WebUI.
 */
object HUD : Module("HUD", Category.RENDER) {

    /** A single HUD line. */
    data class HUDEntry(
        val name: String,
        val value: String = "",
        val highlight: Boolean = false,
        /** True = module currently enabled (draw red). */
        val isRed: Boolean = false
    )

    /** Current HUD lines — rebuilt on events, not per tick. */
    @Volatile
    var hudEntries: List<HUDEntry> = emptyList()
        private set

    // ── Position (WebUI configurable) ──

    var posX: Int = 4
        private set
    var posY: Int = 20
        private set

    /** "Left" or "Right" — anchored side of the list (WebUI config). */
    var position by choices("Position", arrayOf("Left", "Right"))

    // ── Configurable display options ──

    var sortMode by choices("Sort", arrayOf("None", "Alphabetical", "Length", "Category"))
    var reversed by boolean("Reversed", false)

    // ═══════════════════════════════════════════
    //  Collection (event-driven)
    // ═══════════════════════════════════════════

    /** Rebuild the line list. Cheap; called on enable / config change / toggle. */
    fun refreshLines() {
        if (!enabled) return
        val entries = ModuleRegistry.getEnabled()
            .filter { it is HudLineProvider && !it.hudHidden }
            .sortedWith(comparator())
            .map { module ->
                val provider = module as HudLineProvider
                HUDEntry(
                    name = module.name,
                    value = provider.hudValue(),
                    highlight = provider.hudHighlight(),
                    isRed = module.enabled && module.showRedIndicator &&
                        module.category !in Module.silentCategories
                )
            }
        hudEntries = entries
        val names = entries.joinToString(" | ") { it.name }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }

    private fun comparator(): Comparator<Module> = Comparator { a, b ->
        when (sortMode) {
            "Alphabetical" -> a.name.compareTo(b.name)
            "Length" -> a.name.length.compareTo(b.name.length)
            "Category" -> {
                val c = a.category.ordinal.compareTo(b.category.ordinal)
                if (c != 0) c else a.name.compareTo(b.name)
            }
            else -> 0
        }
    }.let { if (reversed) it.reversed() else it }

    // ═══════════════════════════════════════════
    //  Config change → refresh once (not per tick)
    // ═══════════════════════════════════════════

    /** Fires when any option under a module changes → rebuild lines once. */
    private val configListener: () -> Unit = { refreshLines() }

    /** Modules we already subscribed a dirty-listener for (avoid duplicates). */
    private val subscribedModules = mutableSetOf<String>()

    private fun listenToModuleConfigs() {
        for (module in ModuleRegistry.getEnabled()) {
            if (subscribedModules.add(module.name)) {
                ConfigManager.onModuleDirty(module.name, configListener)
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════

    override fun onEnable() {
        lastEnabledKey = ""
        refreshLines()
        listenToModuleConfigs()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.hudTextLine = ""
        hudEntries = emptyList()
    }

    // Safety net: if a module toggled but no config event fired, re-collect.
    // Cost is a cheap string diff per tick; actual rebuild only on change.
    private var lastEnabledKey = ""

    /** Poke from the WebUI (e.g. hudHidden toggled) → re-collect immediately. */
    fun notifyModuleToggled() {
        refreshLines()
        listenToModuleConfigs()
    }

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (!enabled) return@tickListener
        val key = ModuleRegistry.getEnabled().map { it.name }.sorted().joinToString(",")
        if (key != lastEnabledKey) {
            lastEnabledKey = key
            refreshLines()
            listenToModuleConfigs()
        }
    }
}
