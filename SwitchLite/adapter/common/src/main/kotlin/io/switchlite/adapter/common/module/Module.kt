package io.switchlite.adapter.common.module

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
    var enabled: Boolean = false
        private set

    fun enable() {
        if (enabled) return
        enabled = true
        onEnable()
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        onDisable()
    }

    fun toggle() {
        if (enabled) disable() else enable()
    }

    open fun onEnable() {}
    open fun onDisable() {}

    // ── Config caching ──

    private var configDirty: Boolean = true
    @Volatile private var configCache: Any? = null
    private var taggedCaches: MutableMap<String, Any?>? = null

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
