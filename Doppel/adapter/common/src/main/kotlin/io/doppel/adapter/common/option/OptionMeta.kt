package io.doppel.adapter.common.option

/**
 * Option type discriminator for JSON coercion.
 */
enum class OptionType {
    FLOAT, INT, BOOLEAN, STRING, ENUM, CHOICES, TRIGGER_OPTIONS, PROBABILITY
}

/**
 * Metadata for a registered option.
 * Carries validation logic and display info for GUI rendering.
 */
data class OptionMeta(
    val type: OptionType,
    val default: Any,
    val unit: String = "",
    val rangeMin: Float = Float.MIN_VALUE,
    val rangeMax: Float = Float.MAX_VALUE,
    val intRangeMin: Int = Int.MIN_VALUE,
    val intRangeMax: Int = Int.MAX_VALUE,
    val choices: Array<String>? = null
) {
    /**
     * Validate a candidate value against this option's constraints.
     * Returns true if the value is acceptable.
     */
    fun validate(value: Any?): Boolean {
        if (value == null) return false
        return when (type) {
            OptionType.FLOAT -> {
                val v = (value as? Number)?.toFloat() ?: return false
                v in rangeMin..rangeMax
            }
            OptionType.INT -> {
                val v = (value as? Number)?.toInt() ?: return false
                v in intRangeMin..intRangeMax
            }
            OptionType.BOOLEAN -> value is Boolean
            OptionType.STRING -> value is String
            OptionType.CHOICES -> {
                val v = value as? String ?: return false
                choices?.contains(v) ?: true
            }
            OptionType.ENUM -> value is Enum<*>
            OptionType.TRIGGER_OPTIONS -> true // validated by builder
            OptionType.PROBABILITY -> {
                val v = (value as? Number)?.toInt() ?: return false
                v in intRangeMin..intRangeMax
            }
        }
    }

    /**
     * Human-readable list of choices for this option (null if not a choice-like type).
     */
    fun displayChoices(): List<String>? = choices?.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OptionMeta) return false
        return type == other.type && default == other.default && unit == other.unit
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + default.hashCode()
        result = 31 * result + unit.hashCode()
        return result
    }
}

/**
 * A single registered option, described for GUI consumption.
 * Produced by [ConfigManager.optionsOf].
 */
data class OptionDescriptor(
    /** Fully-qualified key: "ModuleName.OptionName" */
    val key: String,
    val type: OptionType,
    val meta: OptionMeta
) {
    /** Short option name (without module prefix). */
    val name: String get() = key.substringAfter('.')
}
