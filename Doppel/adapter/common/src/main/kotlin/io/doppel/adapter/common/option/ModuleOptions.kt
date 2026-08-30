package io.doppel.adapter.common.option

import io.doppel.adapter.common.module.Module

/**
 * Enumerates a module's option properties so the WebUI shows the FULL config list.
 *
 * Option delegates (`boolean/float/int/choices/...`) register into
 * [ConfigManager] lazily — the key is created on the FIRST property read.
 * Many of a module's options are `private val by delegate`, only read inside
 * `cachedConfig { ... }` when that module runs. A disabled module (or an option
 * on an un-taken code path) never registers → its options would be missing.
 *
 * We therefore force each delegated getter to run at registration time. To keep
 * the WebUI option order identical to the module's source declaration order, we
 * walk the module's *fields* (`xxx$delegate` appear in class-file declaration
 * order, which mirrors source order far more reliably than getDeclaredMethods,
 * whose order is unspecified), then invoke the matching getXxx() accessor.
 *
 * Pure JDK reflection — no kotlin-reflect dependency (keeps the fat jar small).
 */
object ModuleOptions {

    /**
     * Force-register all option-delegate properties in source-declaration order.
     * Idempotent; never throws.
     */
    fun forceRegisterAll(module: Module) {
        try {
            val fields = module.javaClass.declaredFields
            for (f in fields) {
                val fieldName = f.name
                // Kotlin `val x by delegate` compiles to a field named "x$delegate".
                if (!fieldName.endsWith("\$delegate")) continue
                val propName = fieldName.removeSuffix("\$delegate")
                val getter = findGetter(module, propName) ?: continue
                try {
                    if (!getter.isAccessible) {
                        try { getter.isAccessible = true } catch (_: Exception) {}
                    }
                    getter.invoke(module)
                } catch (e: Exception) {
                    // Not an option delegate or not invokable — skip.
                }
            }
        } catch (e: Throwable) {
            // Reflection must never break module registration.
        }
    }

    /** Find the getter `get<Prop>` / `is<Prop>` for a property name. */
    private fun findGetter(module: Module, propName: String): java.lang.reflect.Method? {
        val capitalized = propName.replaceFirstChar { it.uppercaseChar() }
        val names = listOf("get$capitalized", "is$capitalized")
        for (n in names) {
            try {
                val m = module.javaClass.getDeclaredMethod(n)
                if (m.parameterCount == 0) return m
            } catch (_: NoSuchMethodException) {
                // try next
            }
        }
        return null
    }
}
