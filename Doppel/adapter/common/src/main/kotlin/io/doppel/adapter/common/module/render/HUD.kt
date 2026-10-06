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
import io.doppel.adapter.common.option.optionPairOf
import io.doppel.adapter.common.option.string
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.adapter.common.option.OptionMeta
import io.doppel.adapter.common.option.OptionType
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
object HUD : Module("HUD", Category.VISUAL) {

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

    /**
     * "Right" or "Left" — the edge the module list hangs off, for the external overlay and the
     * in-game list alike.
     *
     * Right is the default because it is the reference overlay's own placement; the option used to
     * default to Left for the in-game card alone, and it never reached the overlay at all (the
     * contract had no field for it), which is why changing it here did nothing.
     */
    var position by choices("Position", arrayOf("Right", "Left"))

    // "Sort" and "Reversed" used to pick the order of the published rows. The overlay draws the
    // reference's width staircase unconditionally now (longest row first, which is what the user
    // asked for), so those two only ever re-ordered a list nobody reads in that order any more —
    // they are gone rather than left as switches that do nothing.

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
        val ordered: Boolean,
        val positionLeft: Boolean
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
        // The overlay always draws the reference's width staircase: longest row first. The user
        // asked for exactly that, and with the HUD-list panel gone there is no way to arrange rows
        // anyway — a manually stored order (from the rounds when there was one) must not silently
        // win over the length order.
        ordered = false,
        positionLeft = position == "Left"
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
            orderPublished = s.ordered,
            positionLeft = s.positionLeft
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
    //  Keybind toggle notice
    // ═══════════════════════════════════════════

    /** How long "X ON" / "X OFF" stays in the list after a key press. */
    private const val TOGGLE_NOTICE_MS = 1_500L

    private data class ToggleNotice(val name: String, val enabled: Boolean, val until: Long)

    @Volatile
    private var toggleNotice: ToggleNotice? = null

    /**
     * Show a short-lived line for a module that was just toggled by a key press.
     *
     * It is a *value* on an existing row rather than a new mechanism: the writer already publishes a
     * name plus a value, and a module that just switched off has no row of its own any more, so the
     * confirmation has to carry the name itself.
     */
    fun noticeToggle(name: String, enabled: Boolean) {
        // With the HUD module off there is no list to put a line in, and refreshLines would only
        // log a warning about it.
        if (!this.enabled) return
        toggleNotice = ToggleNotice(name, enabled, System.currentTimeMillis() + TOGGLE_NOTICE_MS)
        refreshLines()
    }

    /** The notice row, while it is still live — or null once it has lapsed. */
    private fun liveNoticeRow(): HUDEntry? {
        val notice = toggleNotice ?: return null
        if (System.currentTimeMillis() >= notice.until) return null
        return HUDEntry(
            name = notice.name,
            value = if (notice.enabled) "ON" else "OFF",
            highlight = true,
            isRed = false
        )
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
        val notice = liveNoticeRow()
        val entries = buildList {
            // Title row ("Doppel") at the top — matches the reference layout.
            add(HUDEntry(name = "Doppel", value = "", highlight = false, isRed = false))
            // The keybind confirmation goes above the module rows: it is about what just happened,
            // not about the list's own order.
            if (notice != null) add(notice)
            addAll(
                // Registry order. What the overlay draws does not depend on it: the rows are always
                // sorted by their rendered length there, and that is measured in the overlay's own
                // font, which the Kotlin side cannot do.
                ModuleRegistry.getEnabled().filter { !it.hudHidden }
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
     * Auto-derive a HUD value for a module: its Min/Max range when it has one, else the first
     * numeric option, rendered as "value+unit". Returns ("",false) for toggle-only modules
     * (no numeric option) so they show name only.
     *
     * The range case is the general version of the reported Velocity problem: a module whose two
     * options are a range must show BOTH ends ("25-55 ms"), because showing only one of them is how
     * a configured 0%..100% axis ended up reading as a single derived "50%". Velocity, Reach and
     * AutoClicker publish their own string through [HudLineProvider] and never reach this.
     *
     * Formatted through each option's own declared precision (`OptionMeta.formatNumber`), which is
     * the same rule the WebUI formats with — the panel and this published string are the same text.
     */
    internal fun autoHudValue(module: Module): Pair<String, Boolean> {
        val numeric = ConfigManager.optionsOf(module.name).filter {
            it.type == OptionType.FLOAT || it.type == OptionType.INT
        }

        for (desc in numeric) {
            val pair = optionPairOf(desc.name) ?: continue
            if (!pair.isMin) continue
            val partner = numeric.firstOrNull { it.name == pair.partner } ?: continue
            val mine = ConfigManager.currentValue(desc.key) as? Number ?: continue
            val theirs = ConfigManager.currentValue(partner.key) as? Number ?: continue
            val text = rangeText(desc.meta, partner.meta, mine, theirs)
            val unit = desc.meta.unit.ifEmpty { partner.meta.unit }
            return (if (unit.isNotEmpty()) "$text $unit" else text) to true
        }

        for (desc in numeric) {
            val num = ConfigManager.currentValue(desc.key) as? Number ?: continue
            val text = desc.meta.formatNumber(num)
            val unit = desc.meta.unit
            return (if (unit.isNotEmpty()) "$text $unit" else text) to true
        }
        return "" to false
    }

    /**
     * `25-55`, collapsing to one value when both ends are equal, and printing the smaller end first
     * even if the pair somehow arrives inverted — the option layer keeps pairs ordered, but a HUD
     * value must not depend on that to stay readable.
     */
    private fun rangeText(minMeta: OptionMeta, maxMeta: OptionMeta, min: Number, max: Number): String {
        val ordered = min.toDouble() <= max.toDouble()
        val low = if (ordered) min else max
        val high = if (ordered) max else min
        val lowText = (if (ordered) minMeta else maxMeta).formatNumber(low)
        val highText = (if (ordered) maxMeta else minMeta).formatNumber(high)
        return if (lowText == highText) lowText else "$lowText-$highText"
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
        // Keybind toggles are announced through the HUD the user is already watching.
        EventBridge.registerToggleNoticeHandler { name, enabled -> noticeToggle(name, enabled) }
        refreshLines()
        listenToModuleConfigs()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.clearToggleNoticeHandler()
        toggleNotice = null
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
            // The keybind notice is a countdown rather than an event, so it has to be collected
            // again when it lapses — otherwise "WTap OFF" would sit in the list until something else
            // happened to rebuild it.
            val notice = toggleNotice
            if (notice != null && System.currentTimeMillis() >= notice.until) {
                toggleNotice = null
                refreshLines()
            }
        }
    }
}
