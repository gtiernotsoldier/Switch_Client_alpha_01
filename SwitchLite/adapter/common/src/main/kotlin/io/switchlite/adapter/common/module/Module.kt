package io.switchlite.adapter.common.module

/**
 * Base class for all modules.
 * Provides lifecycle management and enabled state.
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
}
