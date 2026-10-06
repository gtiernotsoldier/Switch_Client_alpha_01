package io.doppel.adapter.common.option

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.doppel.core.logging.CoreLogger
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
    private val values = linkedMapOf<String, Any>()

    /** Registered option metadata (LinkedHashMap → keeps module-declaration order). */
    private val registry = linkedMapOf<String, OptionMeta>()

    /** Change listeners: key = option key, listeners notified on value change. */
    private val listeners = mutableMapOf<String, MutableList<(Any) -> Unit>>()

    /** Module-level dirty callbacks: key = "ModuleName", fired when any option changes. */
    private val moduleDirtyCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()

    /** Register a callback invoked when any option under [moduleName] changes. */
    fun onModuleDirty(moduleName: String, callback: () -> Unit) {
        moduleDirtyCallbacks.getOrPut(moduleName) { mutableListOf() }.add(callback)
    }

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
            // Notify module-level dirty callbacks (config caching)
            val moduleName = key.substringBefore('.')
            moduleDirtyCallbacks[moduleName]?.forEach { it() }
        }
        // A Min/Max pair that has just been inverted is a crash waiting in the module that reads it,
        // so the sibling is pulled along. Done after the change is announced, and it recurses once
        // (the sibling's own check then finds the pair ordered and stops).
        enforcePairOrder(key, value as? Number ?: return)
    }

    // ========== Min/Max pair ordering ==========

    /**
     * Keep a module's Min/Max option pair ordered.
     *
     * Every ranged module declares its range as two options — `HorizontalMin`/`HorizontalMax`,
     * `MinCPS`/`MaxCPS`, `ActionMsMin18`/`ActionMsMax18` — and the consumers feed them straight into
     * `Random.nextInt(min, max + 1)`, which **throws** when `min > max` and silently misbehaves in
     * the float paths. Nothing stopped the WebUI, a hand-edited config or a half-applied JSON import
     * from inverting a pair, so the rule is enforced here, at the one place every write goes
     * through: setting the minimum above the maximum raises the maximum, and setting the maximum
     * below the minimum lowers the minimum.
     *
     * The sibling is derived from the *name* ([optionPairOf]), so no module has to declare its pairs
     * and a module added later gets the behaviour for free. A name with no partner, a partner that
     * is not registered, or a partner whose own range cannot hold the value is skipped — the last
     * one is what happens to `KeepSprint`'s `MinReach` (0..1.5) against `MaxReach` (2.5..3), whose
     * ranges do not overlap, and to which the ordered world is the one they already live in.
     */
    private fun enforcePairOrder(key: String, value: Number) {
        val dot = key.indexOf('.')
        if (dot <= 0) return
        val module = key.substring(0, dot)
        val pair = optionPairOf(key.substring(dot + 1)) ?: return
        val partnerKey = "$module.${pair.partner}"
        val theirs = (values[partnerKey] as? Number)?.toDouble() ?: return
        val mine = value.toDouble()

        val inverted = if (pair.isMin) mine > theirs else mine < theirs
        if (!inverted) return
        val converted = asDeclaredNumber(partnerKey, mine) ?: return
        set(partnerKey, converted)
    }

    /** [v] converted to the type its option was registered with, so the store never holds a Double. */
    private fun asDeclaredNumber(key: String, v: Double): Any? = when (registry[key]?.type) {
        OptionType.FLOAT -> v.toFloat()
        OptionType.INT, OptionType.PROBABILITY, OptionType.COLOR -> v.toInt()
        else -> null
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
        // A reset bypasses `set`, so the pair rule has to be applied here too: the defaults are
        // ordered by construction, but a module that declared them the other way round would
        // otherwise leave an inverted pair behind with no write to catch it.
        (meta.default as? Number)?.let { enforcePairOrder(key, it) }
    }

    /**
     * Reset all options to defaults.
     */
    fun resetAll() {
        for ((key, meta) in registry) {
            values[key] = meta.default
        }
        // One enforced pass afterwards rather than per key, so the result does not depend on the
        // order the registry happens to iterate in.
        for (key in registry.keys.toList()) {
            (values[key] as? Number)?.let { enforcePairOrder(key, it) }
        }
        CoreLogger.info("[ConfigManager] All options reset to defaults")
    }

    // ========== Introspection (for GUI) ==========

    fun getRegisteredKeys(): Set<String> = registry.keys.toSet()

    fun getMeta(key: String): OptionMeta? = registry[key]

    // ========== GUI enumeration API ==========

    /**
     * All registered options of one module, in registration order.
     * Powers the ClickGUI settings panel.
     *
     * Options registered with `hidden = true` — a module's keybind, its red work-flash
     * flag — are filtered out: they are persisted state, not things to tune, and a
     * "-1 Keybind" slider in the panel is worse than useless.
     */
    fun optionsOf(moduleName: String): List<OptionDescriptor> {
        val prefix = "$moduleName."
        return registry.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .filter { !it.value.hidden }
            .map { (key, meta) -> OptionDescriptor(key, meta.type, meta) }
            .toList()
    }

    /**
     * Current runtime value of an option (null if not registered).
     */
    fun currentValue(key: String): Any? = values[key]

    /**
     * Safe typed read with default fallback.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> read(key: String, default: T): T {
        return (values[key] as? T) ?: default
    }

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
     *
     * Every storeable option type must be handled here or it silently fails to
     * reload: an unhandled type fell through to `raw`, which the validator then
     * rejected (that is how ENUM options were lost on every load). The store keeps
     * the *declared* type — a FLOAT stays fractional, a colour stays an Int, an
     * ENUM stays the constant.
     */
    private fun coerceType(key: String, raw: Any): Any? {
        val meta = registry[key] ?: return raw
        return try {
            when (meta.type) {
                OptionType.FLOAT -> (raw as? Number)?.toFloat()
                OptionType.INT -> (raw as? Number)?.toInt()
                OptionType.BOOLEAN -> raw as? Boolean
                OptionType.STRING -> raw as? String
                OptionType.CHOICES -> raw as? String
                OptionType.PROBABILITY -> (raw as? Number)?.toInt()
                OptionType.COLOR -> coerceColor(raw)
                OptionType.ENUM -> coerceEnum(meta, raw)
                // TRIGGER_OPTIONS is derived from its builder; never loaded from disk.
                OptionType.TRIGGER_OPTIONS -> null
            }
        } catch (e: Exception) {
            CoreLogger.warn("[ConfigManager] Type coercion failed for '$key': $raw")
            null
        }
    }

    /** Enum constant by name (what the WebUI and the config file both carry). */
    private fun coerceEnum(meta: OptionMeta, raw: Any): Any? {
        val name = when (raw) {
            is String -> raw
            is Enum<*> -> raw.name
            else -> return null
        }
        val constants = (meta.default as? Enum<*>)?.javaClass?.enumConstants ?: return null
        return constants.firstOrNull { (it as? Enum<*>)?.name == name }
    }

    /** Colour as 0x00RRGGBB — accepts an int or a "#RRGGBB" / "0xRRGGBB" string. */
    private fun coerceColor(raw: Any): Int? {
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().let {
                when {
                    it.startsWith("#") -> it.substring(1).toIntOrNull(16)
                    it.startsWith("0x", true) -> it.substring(2).toIntOrNull(16)
                    else -> it.toIntOrNull()
                }
            } ?: return null
            else -> return null
        }
        return value and COLOR_MAX
    }
}

/** A Min/Max sibling, and which side of the pair the name it was derived from belongs to. */
internal data class OptionPair(val partner: String, val isMin: Boolean)

/**
 * The sibling of a Min/Max option name, or null when the name carries no such token.
 *
 * The **last** `min`/`max` occurrence is the one that names the side: `MaxSneakTimeMin` pairs with
 * `MaxSneakTimeMax` (its trailing `Min` is the side, not the leading `Max`), and the replacement
 * keeps the original casing, so `MinCPS` becomes `MaxCPS` rather than `maxCPS`.
 *
 * Pure and internal so it can be tested against every option name the modules actually declare.
 */
internal fun optionPairOf(optionName: String): OptionPair? {
    val lower = optionName.lowercase()
    val minAt = lower.lastIndexOf("min")
    val maxAt = lower.lastIndexOf("max")
    val at = maxOf(minAt, maxAt)
    if (at < 0 || at + 3 > optionName.length) return null
    val isMin = minAt > maxAt
    val token = optionName.substring(at, at + 3)
    val replacement = if (isMin) "max" else "min"
    val cased = when {
        token == token.uppercase() -> replacement.uppercase()
        token[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
        else -> replacement
    }
    return OptionPair(optionName.substring(0, at) + cased + optionName.substring(at + 3), isMin)
}
