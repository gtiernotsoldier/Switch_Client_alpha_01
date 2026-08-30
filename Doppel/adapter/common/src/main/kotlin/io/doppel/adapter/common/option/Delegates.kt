package io.doppel.adapter.common.option

import io.doppel.core.option.TriggerOptions
import io.doppel.core.option.ProbabilityOption
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Writable property delegates for module options.
 *
 * Each delegate:
 * 1. Registers itself with ConfigManager on first access (with module-scoped key).
 * 2. Reads/writes through ConfigManager (validated, logged, hot-loadable).
 * 3. Supports `var` — GUI and JSON hot-load can mutate values at runtime.
 *
 * Key format: "ModuleName.OptionName" — derived from the owning Module's name
 * and the property name passed to the delegate factory.
 */

// ========== Float ==========

fun float(
    name: String,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = ""
): ReadWriteProperty<Any?, Float> {
    require(default in range) { "Default value $default must be in range $range" }
    return object : ReadWriteProperty<Any?, Float> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): Float {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<Float>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, default, OptionMeta(
                type = OptionType.FLOAT,
                default = default,
                unit = unit,
                rangeMin = range.start,
                rangeMax = range.endInclusive
            ))
            key = k
            return k
        }
    }
}

// ========== Int ==========

fun int(
    name: String,
    default: Int,
    range: IntRange,
    unit: String = ""
): ReadWriteProperty<Any?, Int> {
    require(default in range) { "Default value $default must be in range $range" }
    return object : ReadWriteProperty<Any?, Int> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): Int {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<Int>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, default, OptionMeta(
                type = OptionType.INT,
                default = default,
                unit = unit,
                intRangeMin = range.first,
                intRangeMax = range.last
            ))
            key = k
            return k
        }
    }
}

// ========== Boolean ==========

fun boolean(
    name: String,
    default: Boolean
): ReadWriteProperty<Any?, Boolean> {
    return object : ReadWriteProperty<Any?, Boolean> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<Boolean>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, default, OptionMeta(
                type = OptionType.BOOLEAN,
                default = default
            ))
            key = k
            return k
        }
    }
}

// ========== Enum ==========

fun <T : Enum<T>> enum(
    name: String,
    default: T
): ReadWriteProperty<Any?, T> {
    return object : ReadWriteProperty<Any?, T> {
        private var key: String? = null

        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<T>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, default, OptionMeta(
                type = OptionType.ENUM,
                default = default
            ))
            key = k
            return k
        }
    }
}

// ========== Choices (String-based) ==========

fun choices(
    name: String,
    options: Array<String>
): ReadWriteProperty<Any?, String> {
    return object : ReadWriteProperty<Any?, String> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): String {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<String>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, options[0], OptionMeta(
                type = OptionType.CHOICES,
                default = options[0],
                choices = options
            ))
            key = k
            return k
        }
    }
}

// ========== TriggerOptions ==========

fun triggerOptions(
    name: String,
    builder: TriggerOptions.Builder.() -> Unit
): ReadWriteProperty<Any?, TriggerOptions> {
    /**
     * TriggerOptions delegate with live rebuild.
     *
     * Unlike other delegates that cache a default value, this rebuilds the
     * TriggerOptions on every getValue() by re-executing the builder lambda.
     * This is necessary because the builder references other property delegates
     * (e.g. `onlyGround = onlyPlane`) that the user can change at runtime
     * via the GUI. A cached snapshot would miss those changes.
     *
     * Cost: negligible — Builder sets a few booleans/ints per tick per module.
     */
    return object : ReadWriteProperty<Any?, TriggerOptions> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): TriggerOptions {
            resolveKey(thisRef, name, property) // register metadata on first access
            return TriggerOptions.Builder().apply(builder).build()
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: TriggerOptions) {
            // No-op: triggerOptions is always computed from its builder + live delegates.
            // Direct set is not supported; change the individual boolean options instead.
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            val placeholder = TriggerOptions.Builder().apply(builder).build()
            ConfigManager.register(k, placeholder, OptionMeta(
                type = OptionType.TRIGGER_OPTIONS,
                default = placeholder
            ))
            key = k
            return k
        }
    }
}

// ========== Probability ==========

fun probability(
    name: String,
    default: Int,
    range: IntRange
): ReadWriteProperty<Any?, ProbabilityOption> {
    require(default in range) { "Default value must be in range $range" }
    return object : ReadWriteProperty<Any?, ProbabilityOption> {
        private var key: String? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): ProbabilityOption {
            val k = resolveKey(thisRef, name, property)
            return ConfigManager.get<ProbabilityOption>(k)
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: ProbabilityOption) {
            val k = resolveKey(thisRef, name, property)
            ConfigManager.set(k, value)
        }

        private fun resolveKey(thisRef: Any?, optName: String, property: KProperty<*>): String {
            key?.let { return it }
            val modulePrefix = resolveModulePrefix(thisRef)
            val k = "$modulePrefix.$optName"
            ConfigManager.register(k, ProbabilityOption(default), OptionMeta(
                type = OptionType.PROBABILITY,
                default = ProbabilityOption(default),
                intRangeMin = range.first,
                intRangeMax = range.last
            ))
            key = k
            return k
        }
    }
}

// ========== Module Prefix Resolution ==========

/**
 * Resolve the module name prefix for option keys.
 * If the owning object is a Module, use its name; otherwise use the class simple name.
 */
private fun resolveModulePrefix(thisRef: Any?): String {
    if (thisRef == null) return "Unknown"
    return try {
        // Module has a `name` property
        val nameProp = thisRef::class.java.getMethod("getName")
        nameProp.invoke(thisRef) as? String ?: thisRef::class.java.simpleName
    } catch (e: Exception) {
        thisRef::class.java.simpleName
    }
}
