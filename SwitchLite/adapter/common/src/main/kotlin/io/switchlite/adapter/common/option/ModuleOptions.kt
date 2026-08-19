package io.switchlite.adapter.common.option

import io.switchlite.adapter.common.module.Module

/**
 * Enumerates a module's option properties so the WebUI shows the FULL config list.
 *
 * Why this exists: option delegates (`boolean/float/int/choices/...`) register
 * into [ConfigManager] lazily — the key is created on the FIRST property read.
 * Many of a module's options are `private val by delegate`, only read inside
 * `cachedConfig { ... }` when that module runs. A disabled module (or an option
 * on an un-taken code path) never registers → its options would be missing.
 *
 * We therefore invoke every `getXxx()` accessor on the module at registration
 * time, forcing each delegated getter to run and register its option.
 *
 * Pure JDK reflection — no kotlin-reflect dependency (keeps the fat jar small).
 */
object ModuleOptions {

    /**
     * Force-register all option-delegate properties of [module] by invoking its
     * `get*()` methods via JDK reflection. Idempotent; never throws.
     */
    fun forceRegisterAll(module: Module) {
        try {
            val methods = module.javaClass.declaredMethods
            for (m in methods) {
                val name = m.name
                if (!name.startsWith("get") || name.length <= 3) continue
                if (m.parameterCount != 0) continue
                if (m.returnType == Void.TYPE) continue
                try {
                    if (!m.isAccessible) {
                        try { m.isAccessible = true } catch (_: Exception) {}
                    }
                    m.invoke(module)
                } catch (e: Exception) {
                    // Not an option delegate or not invokable — skip.
                }
            }
        } catch (e: Throwable) {
            // Reflection must never break module registration.
        }
    }
}
