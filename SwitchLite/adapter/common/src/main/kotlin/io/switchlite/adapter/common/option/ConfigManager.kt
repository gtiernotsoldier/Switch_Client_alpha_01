package io.switchlite.adapter.common.option

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.switchlite.core.logging.CoreLogger
import java.io.File

/**
 * Central configuration registry and hot-load manager.
 *
 * Constitution compliance:
 * - §1 Safety: all writes go through validation (range, choices).
 * - §2 Debuggability: logs every value change; supports JSON replay.
 * - §3 Strategy: JSON hot-load enables cloud strategy pack updates without recompilation.
 *
 * Modules register their options via delegates. ConfigManager stores current values
 * and can load/save them as JSON keyed by "ModuleName.optionName".
 */
object ConfigManager {

    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    /** Current values: key = "ModuleName.optionName", value = current runtime value. */
    private val values = mutableMapOf<String, Any>()

    /** Registered option metadata for validation and serialization. */
    private val registry = mutableMapOf<String, OptionMeta>()

    /** Change listeners: key = option key, listeners notified on value change. */
    private val listeners = mutableMapOf<String, MutableList<(Any) -> Unit>>()

    // ========== Registration ==========

    fun register(key: String, default: Any, meta: OptionMeta) {
        values.putIfAbsent(key, default)
        registry[key] = meta
    }

    // ========== Read / Write ==========

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T {
        return values[key] as? T ?: throw IllegalStateException("Option '$key' not registered")
    }

    fun <T> set(key: String, value: T) {
        val meta = registry[key]
        if (meta != null && !meta.validate(value)) {
            CoreLogger.warn("[ConfigManager] Rejected invalid value for '$key': $value")
            return
        }
        val old = values[key]
        values[key] = value as Any
        if (old != value) {
            CoreLogger.debug("[ConfigManager] $key: $old -> $value")
            listeners[key]?.forEach { it(value as Any) }
        }
    }

    // ========== Listeners ==========

    fun onChange(key: String, listener: (Any) -> Unit) {
        listeners.getOrPut(key) { mutableListOf() }.add(listener)
    }

    // ========== Persistence ==========

    /**
     * Load configuration from a JSON file. Unknown keys are ignored.
     * Values are validated before applying; invalid values are skipped with a warning.
     */
    fun load(file: File) {
        if (!file.exists()) {
            CoreLogger.info("[ConfigManager] Config file not found: ${file.name}, using defaults")
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val json = mapper.readValue(file, Map::class.java) as Map<String, Any>
            applyMap(json)
            CoreLogger.info("[ConfigManager] Loaded config from ${file.name}")
        } catch (e: Exception) {
            CoreLogger.error("[ConfigManager] Failed to load ${file.name}: ${e.message}")
        }
    }

    /**
     * Load from a JSON string (for cloud hot-update).
     */
    fun loadFromString(json: String) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map = mapper.readValue(json, Map::class.java) as Map<String, Any>
            applyMap(map)
            CoreLogger.info("[ConfigManager] Loaded config from string")
        } catch (e: Exception) {
            CoreLogger.error("[ConfigManager] Failed to parse config string: ${e.message}")
        }
    }

    /**
     * Save all current values to a JSON file.
     */
    fun save(file: File) {
        try {
            file.parentFile?.mkdirs()
            mapper.writeValue(file, values)
            CoreLogger.info("[ConfigManager] Saved config to ${file.name}")
        } catch (e: Exception) {
            CoreLogger.error("[ConfigManager] Failed to save config: ${e.message}")
        }
    }

    /**
     * Serialize current values to JSON string.
     */
    fun toJson(): String = mapper.writeValueAsString(values)

    // ========== Reset ==========

    /**
     * Reset a single option to its registered default.
     */
    fun resetOption(key: String) {
        val meta = registry[key] ?: return
        values[key] = meta.default
        listeners[key]?.forEach { it(meta.default) }
    }

    /**
     * Reset all options to defaults.
     */
    fun resetAll() {
        for ((key, meta) in registry) {
            values[key] = meta.default
        }
        CoreLogger.info("[ConfigManager] All options reset to defaults")
    }

    // ========== Introspection (for GUI) ==========

    fun getRegisteredKeys(): Set<String> = registry.keys.toSet()

    fun getMeta(key: String): OptionMeta? = registry[key]

    // ========== Internal ==========

    private fun applyMap(json: Map<String, Any>) {
        for ((key, rawValue) in json) {
            if (key !in registry) continue
            val coerced = coerceType(key, rawValue)
            if (coerced != null) {
                set(key, coerced)
            }
        }
    }

    /**
     * Jackson deserializes numbers as Int/Double. Coerce to the registered type.
     */
    private fun coerceType(key: String, raw: Any): Any? {
        val meta = registry[key] ?: return raw
        return try {
            when (meta.type) {
                OptionType.FLOAT -> (raw as? Number)?.toFloat()
                OptionType.INT -> (raw as? Number)?.toInt()
                OptionType.BOOLEAN -> raw as? Boolean
                OptionType.STRING -> raw as? String
                else -> raw
            }
        } catch (e: Exception) {
            CoreLogger.warn("[ConfigManager] Type coercion failed for '$key': $raw")
            null
        }
    }
}
