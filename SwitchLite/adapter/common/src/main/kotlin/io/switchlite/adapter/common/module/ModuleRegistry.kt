package io.switchlite.adapter.common.module

import io.switchlite.core.logging.CoreLogger
import io.switchlite.core.safety.SafetyWrapper
import io.switchlite.adapter.common.option.ModuleOptions

/**
 * Central module registry.
 *
 * Responsibilities:
 * - Holds all registered Module instances, keyed by name.
 * - Provides lookup, category filtering, enable/disable/toggle.
 * - Wires SafetyWrapper's auto-disable callback so that modules which
 *   exceed the failure threshold are automatically disabled.
 *
 * Constitution compliance:
 * - §2 Debuggability: logs all lifecycle events.
 * - §1 Safety: auto-disable on repeated strategy failures via SafetyWrapper.
 */
object ModuleRegistry {

    private val modules = linkedMapOf<String, Module>()

    // ========== Registration ==========

    fun register(module: Module) {
        if (modules.containsKey(module.name)) {
            CoreLogger.warn("[ModuleRegistry] Duplicate module name: ${module.name}, overwriting")
        }
        modules[module.name] = module
        // Force-register every option delegate so the WebUI shows the full
        // config list even for options not yet touched by a running module.
        ModuleOptions.forceRegisterAll(module)
        CoreLogger.debug("[ModuleRegistry] Registered: ${module.name} (${module.category})")
    }

    fun registerAll(vararg moduleList: Module) {
        moduleList.forEach { register(it) }
    }

    /**
     * Called once at bootstrap to wire SafetyWrapper → ModuleRegistry auto-disable.
     */
    fun initSafetyIntegration() {
        SafetyWrapper.disableCallback = { moduleId ->
            val module = modules[moduleId]
            if (module != null && module.enabled) {
                CoreLogger.error("[ModuleRegistry] Auto-disabling '$moduleId' due to repeated failures")
                module.disable()
            }
        }
    }

    // ========== Lookup ==========

    fun get(name: String): Module? = modules[name]

    fun getByCategory(category: Category): List<Module> =
        modules.values.filter { it.category == category }

    fun getAll(): List<Module> = modules.values.toList()

    fun getEnabled(): List<Module> = modules.values.filter { it.enabled }

    // ========== Lifecycle ==========

    fun enable(name: String) {
        modules[name]?.enable()
    }

    fun disable(name: String) {
        modules[name]?.disable()
    }

    fun toggle(name: String) {
        modules[name]?.toggle()
    }

    fun disableAll() {
        modules.values.filter { it.enabled }.forEach { it.disable() }
        CoreLogger.info("[ModuleRegistry] All modules disabled")
    }

    // ========== Introspection ==========

    fun isRegistered(name: String): Boolean = modules.containsKey(name)

    fun size(): Int = modules.size
}
