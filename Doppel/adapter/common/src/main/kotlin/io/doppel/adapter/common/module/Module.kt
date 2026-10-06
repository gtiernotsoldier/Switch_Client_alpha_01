package io.doppel.adapter.common.module

import io.doppel.adapter.common.option.ConfigManager
import io.doppel.adapter.common.option.OptionMeta
import io.doppel.adapter.common.option.OptionType

/**
 * Base class for all modules.
 * Provides lifecycle management, enabled state, and config caching.
 *
 * Config caching: when module parameters haven't changed, calling
 * [cachedConfig] reuses the last built config object instead of
 * allocating a new one. The GUI calls [markConfigDirty] on any
 * value change to invalidate the cache.
 */
abstract class Module(
    val name: String,
    val category: Category
) {
    init {
        ConfigManager.onModuleDirty(name) { markConfigDirty() }
        // The keybind and the red-flash flag live in the config store, not in a plain
        // field: only registered options are saved, so as fields they were silently
        // forgotten on every restart — which is exactly how "my keybind stopped
        // working" happens. Both are registered as hidden options so they persist and
        // validate through the normal path without appearing as tunable sliders.
        ConfigManager.register(
            "$name.$KEYBIND_OPTION", NO_KEYBIND,
            OptionMeta(type = OptionType.INT, default = NO_KEYBIND, intRangeMin = -1, intRangeMax = 1000, hidden = true)
        )
        ConfigManager.register(
            "$name.$RED_INDICATOR_OPTION", true,
            OptionMeta(type = OptionType.BOOLEAN, default = true, hidden = true)
        )
    }

    var enabled: Boolean = false
        private set

    /** Whether this module is hidden from the HUD and GUI module list. */
    var hidden: Boolean = false

    /**
     * GLFW key code to toggle this module (`-1` = unbound).
     *
     * Backed by the config store: registered in [init], so it is written to
     * `doppel-config.json` with everything else and survives a restart.
     */
    var keybind: Int
        get() = ConfigManager.get<Int>("$name.$KEYBIND_OPTION")
        set(value) {
            ConfigManager.set("$name.$KEYBIND_OPTION", value)
        }

    /**
     * Whether this module flashes its HUD row red while it is working.
     *
     * Set to false for stealth modules (e.g. AimAssist, AutoClicker) where a visible
     * indicator would be undesirable. Also persisted, for the same reason as the
     * keybind — a stealth module that starts flashing again after a restart is a
     * surprise the user cannot explain.
     */
    var showRedIndicator: Boolean
        get() = ConfigManager.get<Boolean>("$name.$RED_INDICATOR_OPTION")
        set(value) {
            ConfigManager.set("$name.$RED_INDICATOR_OPTION", value)
        }

    /**
     * Whether this module is hidden from the HUD module list. Configured from
     * the WebUI (per-module "show on HUD" toggle). Modules are visible by
     * default. Only meaningful for modules implementing [HudLineProvider].
     */
    var hudHidden: Boolean = false

    /**
     * Whether this module should be included in HUD display.
     * Hidden modules are excluded regardless of enabled state.
     * Modules with showRedIndicator=false are included but shown in default color.
     */
    val visible: Boolean get() = !hidden

    companion object {
        /**
         * Categories whose modules should NOT show red indicator on HUD by default.
         * Individual modules can override this via showRedIndicator.
         */
        val silentCategories = setOf(Category.COMBAT)

        /** How long one work pulse takes to decay, in ms. */
        const val WORK_FLASH_MS: Long = 420L

        /** Option names for the two pieces of per-module state that are persisted but not tuned. */
        const val KEYBIND_OPTION = "Keybind"
        const val RED_INDICATOR_OPTION = "RedIndicator"

        /** The value of an unbound keybind. */
        const val NO_KEYBIND = -1
    }

    fun enable() {
        if (enabled) return
        enabled = true
        io.doppel.core.logging.CoreLogger.info("[Module] ENABLE $name (${category.name})")
        onEnable()
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        io.doppel.core.logging.CoreLogger.info("[Module] DISABLE $name (${category.name})")
        onDisable()
    }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    open fun onEnable() {}
    open fun onDisable() {}

    // ── Toggle via keybind (called by adapter key dispatch) ──

    /**
     * Check if the given GLFW key code matches this module's keybind.
     * Returns true if keybind matches and the module was toggled.
     */
    fun tryKeybindToggle(keyCode: Int): Boolean {
        if (keybind != keyCode) return false
        toggle()
        // Say so. A key press with no visible result is indistinguishable from a key press that
        // never arrived, and that ambiguity is what "my keybind does not work" was.
        io.doppel.adapter.common.api.EventBridge.noticeModuleToggled(name, enabled)
        return true
    }

    // ── Work flash (HUD red pulse while the module actively does something) ──
    // Modules call [markWorked] at the exact instant they act (a tap starts, a
    // click fires, motion is modified). The HUD reads [flashStrength] per frame
    // and pulses the row red — the modernized successor of the legacy isRed bar.
    // Pure time math — unit-tested.

    @Volatile private var flashUntilMs: Long = 0L

    /**
     * Signal "this module just did something" — the HUD row will flash red and
     * decay over [durationMs]. Respects [showRedIndicator] (stealth modules
     * opting out never pulse).
     */
    fun markWorked(durationMs: Long = WORK_FLASH_MS) {
        if (!showRedIndicator) return
        flashUntilMs = System.currentTimeMillis() + durationMs
    }

    /** 1.0 right after [markWorked], linearly decaying to 0.0 when idle. */
    fun flashStrength(nowMs: Long = System.currentTimeMillis()): Float {
        val remain = flashUntilMs - nowMs
        return if (remain <= 0L) 0f else (remain.toFloat() / WORK_FLASH_MS).coerceIn(0f, 1f)
    }

    // ── Config caching ──

    // configDirty must be @Volatile: it is written by the WebUI thread (markConfigDirty)
    // and read by the background tick thread (cachedConfig). Without volatility the write
    // may not be visible, so the tick thread keeps returning the stale cached config and
    // WebUI changes only appear after the module is re-enabled (which bypasses the cache).
    @Volatile private var configDirty: Boolean = true
    @Volatile private var configCache: Any? = null
    @Volatile private var taggedCaches: MutableMap<String, Any?>? = null

    /**
     * Call this from the GUI whenever any option value changes.
     * Invalidates ALL config caches so the next [cachedConfig] rebuilds.
     */
    fun markConfigDirty() {
        configDirty = true
        taggedCaches?.clear()
    }

    /**
     * Build-and-cache pattern: if config is not dirty, returns the
     * cached object. Otherwise calls [builder], caches, clears dirty
     * flag, and returns the new object.
     *
     * Usage in onTick:
     *   val config = cachedConfig { buildConfig() }
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> cachedConfig(builder: () -> T): T {
        if (!configDirty && configCache != null) return configCache as T
        val fresh = builder()
        configCache = fresh
        configDirty = false
        return fresh
    }

    /**
     * Tagged variant for modules with multiple config variants
     * (e.g. AimAssist with SELF_ADAPTIVE vs LEGIT mode).
     * Each tag gets its own cache slot.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> cachedConfig(tag: String, builder: () -> T): T {
        if (taggedCaches == null) taggedCaches = mutableMapOf()
        val map = taggedCaches!!
        if (!configDirty && map.containsKey(tag)) return map[tag] as T
        val fresh = builder()
        map[tag] = fresh
        configDirty = false
        return fresh
    }
}
