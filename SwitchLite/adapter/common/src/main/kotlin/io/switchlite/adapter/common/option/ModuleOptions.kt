package io.switchlite.adapter.common.option

import io.switchlite.adapter.common.module.Module
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Enumerates a module's option properties via Kotlin reflection so the WebUI
 * shows the FULL config list.
 *
 * Why this exists: option delegates (`boolean/float/int/choices/...`) register
 * into [ConfigManager] lazily — the key is created on the FIRST property read.
 * Many of a module's options are `private val by delegate`, so they only get
 * touched inside `cachedConfig { ... }` when that module runs. A disabled
 * module (or an option on an un-taken code path) never registers → its options
 * would be missing from the panel. Reading each delegated property forces the
 * delegate to register it, so every option is always visible.
 */
object ModuleOptions {

    /**
     * Force-register all option-delegate properties of [module] by reading them.
     * Safe to call repeatedly (ConfigManager.putIfAbsent is idempotent).
     */
    fun forceRegisterAll(module: Module) {
        try {
            val props = module::class.memberProperties
            for (prop in props) {
                if (prop is KProperty1<*, *>) {
                    try {
                        // Calling get triggers the delegate's getValue -> registers the option.
                        @Suppress("UNCHECKED_CAST")
                        (prop as KProperty1<Module, Any?>).get(module)
                    } catch (e: Exception) {
                        // Not an option delegate or not readable — skip.
                    }
                }
            }
        } catch (e: Throwable) {
            // Reflection must never break module registration.
        }
    }
}
