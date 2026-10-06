package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.api.KeyNames
import io.doppel.adapter.common.hud.HudStateWriter
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.HudLineProvider
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.color
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.string
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * HUD — transparent in-game module status list (Doppel style).
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

    /** "Left" or "Right" — anchored side of the module list (WebUI config).
     *  The brand card takes the opposite top corner. Default Left (v3.1:
     *  module list top-left, glass brand card top-right). */
    var position by choices("Position", arrayOf("Left", "Right"))

    // ── Configurable display options ──

    var sortMode by choices("Sort", arrayOf("None", "Alphabetical", "Length", "Category"))
    var reversed by boolean("Reversed", false)

    // ── External overlay options (contract v2) ──
    // The overlay's own look, picked from the game/WebUI instead of a command line flag: these go
    // into the v2 state header, and the overlay treats the header as its default while an explicit
    // CLI flag still wins. That is what lets a user switch list effect without relaunching the
    // injector (B1).

    /** Which of the ported HUD's modes the overlay draws (plus this port's Modern mode). */
    var overlayMode by choices("OverlayMode", arrayOf("Default", "Rise", "VapeV4", "Tesseract", "Modern"))

    /** The Default mode's accent animation. */
    var overlayWave by boolean("OverlayWave", true)

    /** Split CamelCase module names in the overlay's list. */
    var overlaySpaced by boolean("OverlaySpaced", false)

    /** Draw the overlay's watermark run. */
    var overlayWatermark by boolean("OverlayWatermark", true)

    /** The wave's two colours, 0x00RRGGBB (reference defaults `0x4E566B` / `0x7892D6`). */
    var overlayPrimary by color("OverlayPrimaryColor", HudStateWriter.DEFAULT_PRIMARY)
    var overlaySecondary by color("OverlaySecondaryColor", HudStateWriter.DEFAULT_SECONDARY)

    /**
     * The overlay's own scale, 1.0 = the reference's physical-pixel layout.
     *
     * 1.0 is written to the header as "unset", so an overlay launched with an explicit `--scale`
     * keeps it until the user actually moves this; every other value wins over the command line.
     */
    var overlayScale by float("OverlayScale", 1.0f, 0.5f..2.0f)

    /** Background opacity percent of the overlay's rows and watermark panel. 100 = the header says "unset". */
    var overlayOpacity by int("OverlayOpacity", 100, 10..100, "%")

    /**
     * The gap between overlay rows, as a percent of the mode's own.
     *
     * Separate from [overlayScale] on purpose: the scale also changes the font, so it cannot be used
     * to loosen a list that is merely too tight. 100 = the header says "unset".
     */
    var overlayLineSpacing by int("OverlayLineSpacing", 100, 50..200, "%")

    /**
     * Row order in the overlay: "Width" is the reference's wedge (widest first), "Published" draws
     * the list in the order the user arranged it (see [moveInHud]).
     */
    var overlayOrder by choices("OverlayOrder", arrayOf("Width", "Published"))

    /**
     * The user's manual HUD order, as a comma-separated list of module names.
     *
     * Stored as a plain string because it is a list, not a value: names not in it keep their
     * alphabetical/category order after the listed ones, so adding a module never invalidates it.
     */
    var hudOrderRaw by string("HudOrder", "")

    // ═══════════════════════════════════════════
    //  Overlay style (cached; rebuilt on config change)
    // ═══════════════════════════════════════════

    /** Everything in the header that comes from an option rather than from the running game. */
    private data class OverlayStyle(
        val wave: Boolean,
        val spaced: Boolean,
        val watermark: Boolean,
        val mode: Int,
        val primary: Int,
        val secondary: Int,
        val scale: Float,
        val opacity: Int,
        val lineSpacing: Int,
        val ordered: Boolean
    )

    // Rebuilt only when an option under this module changes. The render loop asks for the header
    // every frame, and reading eight declared options through ConfigManager each time was pure
    // per-frame overhead for values that change maybe once a session.
    @Volatile
    private var styleCache: OverlayStyle? = null

    private fun style(): OverlayStyle = styleCache ?: buildStyle().also { styleCache = it }

    private fun buildStyle(): OverlayStyle = OverlayStyle(
        wave = overlayWave,
        spaced = overlaySpaced,
        watermark = overlayWatermark,
        mode = HudStateWriter.modeCode(overlayMode),
        primary = overlayPrimary,
        secondary = overlaySecondary,
        // "Unset" is the overlay's own default, so an explicit command line still wins.
        scale = if (overlayScale == 1.0f) 0f else overlayScale,
        opacity = if (overlayOpacity >= 100) 0 else overlayOpacity,
        lineSpacing = if (overlayLineSpacing == 100) 0 else overlayLineSpacing,
        ordered = overlayOrder == "Published"
    )

    /**
     * The contract-v3 header for the external overlay. The option-derived part is cached; the
     * nickname and frame counter are read live, because those change while the game runs.
     */
    fun overlayHeader(): HudStateWriter.Header {
        val s = style()
        return HudStateWriter.Header(
            wave = s.wave,
            spaced = s.spaced,
            watermark = s.watermark,
            mode = s.mode,
            primary = s.primary,
            secondary = s.secondary,
            fps = EventBridge.clientFps,
            nickname = EventBridge.localPlayerName,
            scale = s.scale,
            opacity = s.opacity,
            lineSpacing = s.lineSpacing,
            orderPublished = s.ordered
        )
    }

    /**
     * The work-pulse strength of a module, 0..255, sampled at publish time.
     *
     * `Module.markWorked()` / `flashStrength()` have existed since the module layer was written but
     * nothing ever read them: the HUD published the module's *enabled* flag in the flash byte
     * instead, which is why an active module drew a steady red bar rather than a pulse when it
     * actually did something. Unknown names (the title row) are simply idle.
     */
    fun flashByte(name: String): Int {
        val module = ModuleRegistry.get(name) ?: return 0
        return (module.flashStrength() * 255f).toInt().coerceIn(0, 255)
    }

    // ═══════════════════════════════════════════
    //  Manual HUD order
    // ═══════════════════════════════════════════

    private fun hudOrderList(): List<String> =
        hudOrderRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Every module that can appear on the HUD, in the user's order.
     *
     * Deliberately the whole HUD-capable set rather than the currently enabled rows: the WebUI's
     * up/down buttons must be able to place a module *before* it is switched on, and a disabled
     * module keeps its slot instead of being re-appended when it comes back.
     */
    fun hudOrderedNames(): List<String> {
        val rank = hudOrderList().withIndex().associate { (index, name) -> name to index }
        return ModuleRegistry.getAll()
            .filter { !it.hudHidden }
            .map { it.name }
            .sortedBy { rank[it] ?: Int.MAX_VALUE } // stable: unlisted names keep their own order
    }

    /** Where [name] sits in the HUD order, or -1. The WebUI's row order. */
    fun hudIndexOf(name: String): Int = hudOrderedNames().indexOf(name)

    /**
     * Move one module a slot up or down in the HUD order, persist it and re-collect the rows.
     * Returns false when the name is unknown or already at that end.
     */
    fun moveInHud(name: String, direction: String): Boolean {
        // Validated here rather than only at the HTTP edge: anything that is not "up" used to fall
        // through to the down branch, so a typo moved the row the wrong way instead of being
        // ignored.
        if (direction != "up" && direction != "down") return false
        val order = hudOrderedNames().toMutableList()
        val index = order.indexOf(name)
        if (index < 0) return false
        val target = if (direction == "up") index - 1 else index + 1
        if (target < 0 || target >= order.size) return false
        val moved = order[index]
        order[index] = order[target]
        order[target] = moved
        hudOrderRaw = order.joinToString(",")
        refreshLines()
        return true
    }

    /** Apply the manual order to a module list, leaving unlisted modules where they were. */
    private fun applyHudOrder(modules: List<Module>): List<Module> {
        val rank = hudOrderList().withIndex().associate { (index, name) -> name to index }
        if (rank.isEmpty()) return modules
        return modules.sortedBy { rank[it.name] ?: Int.MAX_VALUE }
    }

    // ═══════════════════════════════════════════
    //  Collection (event-driven)
    // ═══════════════════════════════════════════

    /** Rebuild the line list. Cheap; called on enable / config change / toggle. */
    fun refreshLines() {
        if (!enabled) {
            io.doppel.core.logging.CoreLogger.warn("[HUD] refreshLines skipped — HUD not enabled")
            return
        }
        val entries = buildList {
            // Title row ("Doppel") at the top — matches the reference layout.
            add(HUDEntry(name = "Doppel", value = "", highlight = false, isRed = false))
            addAll(
                applyHudOrder(
                    ModuleRegistry.getEnabled()
                        .filter { !it.hudHidden }
                        .sortedWith(comparator())
                )
                    .map { module ->
                        val provider = module as? HudLineProvider
                        // Custom value if the module overrides; else auto-derive the
                        // module's first numeric option (e.g. STap/WTap). No numeric
                        // option -> name only.
                        val (value, hl) = provider?.hudValue()?.takeIf { it.isNotEmpty() }?.let {
                            it to provider.hudHighlight()
                        } ?: autoHudValue(module)
                        HUDEntry(
                            name = module.name,
                            value = withKeybind(module, value),
                            highlight = hl,
                            isRed = module.enabled && module.showRedIndicator &&
                                module.category !in Module.silentCategories
                        )
                    }
            )
        }
        hudEntries = entries
        val names = entries.drop(1).joinToString(" | ") { it.name }
        EventBridge.hudTextLine = if (names.isNotEmpty()) "Doppel | $names" else "Doppel"
        io.doppel.core.logging.CoreLogger.info("[HUD] refreshLines: ${entries.size} entries, enabled=$enabled")
    }

    /**
     * A row's value with the module's bound key appended: `Legit 80%-100%/100%[N]`.
     *
     * The key is part of the row because that is how the user finds out which key toggles a module
     * without opening the panel — and because an unbound module showing nothing is exactly the
     * ambiguity that made them ask whether binding had worked at all.
     *
     * The contract's value field is 24 bytes, so a very long value plus a key can lose its tail when
     * the writer truncates; module values are short enough that this does not bite in practice.
     */
    internal fun withKeybind(module: Module, value: String): String {
        if (module.keybind <= 0) return value
        val key = "[${KeyNames.name(module.keybind)}]"
        return if (value.isEmpty()) key else "$value$key"
    }

    /**
     * Auto-derive a HUD value for a module: the first numeric option (FLOAT/INT)
     * registered under it, rendered as "value+unit". Returns ("",false) for
     * toggle-only modules (no numeric option) so they show name only.
     *
     * Formatted through the option's own declared precision (`OptionMeta.formatNumber`), which is
     * the same rule the WebUI formats with (B2) — the panel and this published string are now the
     * same text instead of two independent roundings of the same number.
     */
    private fun autoHudValue(module: Module): Pair<String, Boolean> {
        for (desc in ConfigManager.optionsOf(module.name)) {
            if (desc.type == io.doppel.adapter.common.option.OptionType.FLOAT ||
                desc.type == io.doppel.adapter.common.option.OptionType.INT
            ) {
                val v = ConfigManager.currentValue(desc.key) ?: continue
                val num = v as? Number ?: continue
                val text = desc.meta.formatNumber(num)
                val unit = desc.meta.unit
                return (if (unit.isNotEmpty()) "$text $unit" else text) to true
            }
        }
        return "" to false
    }

    private fun comparator(): Comparator<Module> {
        val base = Comparator<Module> { a, b ->
            when (sortMode) {
                "Alphabetical" -> a.name.compareTo(b.name)
                "Length" -> a.name.length.compareTo(b.name.length)
                "Category" -> {
                    val c = a.category.ordinal.compareTo(b.category.ordinal)
                    if (c != 0) c else a.name.compareTo(b.name)
                }
                else -> 0
            }
        }
        return if (reversed) base.reversed() else base
    }

    // ═══════════════════════════════════════════
    //  Config change → refresh once (not per tick)
    // ═══════════════════════════════════════════

    /** Fires when any option under a module changes → rebuild lines once. */
    private val configListener: () -> Unit = {
        // The overlay's header is derived from this module's own options, so a change here has to
        // drop the cached style as well as rebuild the rows.
        styleCache = null
        refreshLines()
    }

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
        if (enabled) {
            val key = ModuleRegistry.getEnabled().map { it.name }.sorted().joinToString(",")
            if (key != lastEnabledKey) {
                lastEnabledKey = key
                refreshLines()
                listenToModuleConfigs()
            }
        }
    }
}
