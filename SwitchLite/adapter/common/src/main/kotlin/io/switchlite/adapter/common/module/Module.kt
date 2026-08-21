package io.switchlite.adapter.common.module

import io.switchlite.adapter.common.option.ConfigManager

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
    }

    var enabled: Boolean = false
        private set

    /** Whether this module is hidden from the HUD and GUI module list. */
    var hidden: Boolean = false

    /**
     * GLFW key code to toggle this module (-1 = unbound).
     * The GUI lets users rebind this at runtime.
     */
    var keybind: Int = -1

    /**
     * Whether this module shows a red indicator on the HUD when active.
     * Set to false for stealth modules (e.g. AimAssist, AutoClicker)
     * where a visible indicator would be undesirable.
     *
     * Note: this is only effective when the global red-indicator toggle
     * (EventBridge.isGuiRedIndicatorEnabled) is ON.
     */
    var showRedIndicator: Boolean = true

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
    }

    fun enable() {
        if (enabled) return
        enabled = true
        io.switchlite.core.logging.CoreLogger.info("[Module] ENABLE $name (${category.name})")
        onEnable()
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        io.switchlite.core.logging.CoreLogger.info("[Module] DISABLE $name (${category.name})")
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
        return true
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
