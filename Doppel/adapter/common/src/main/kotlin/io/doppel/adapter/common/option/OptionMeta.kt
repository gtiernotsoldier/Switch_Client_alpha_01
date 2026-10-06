package io.doppel.adapter.common.option

/**
 * Option type discriminator for JSON coercion.
 */
enum class OptionType {
    FLOAT, INT, BOOLEAN, STRING, ENUM, CHOICES, TRIGGER_OPTIONS, PROBABILITY, COLOR
}

/** Highest value a colour option may hold: 0x00RRGGBB (the v2 HUD header's encoding). */
const val COLOR_MAX: Int = 0xFFFFFF

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
    val choices: Array<String>? = null,
    /**
     * Granularity the option declares for its +/- steps and slider snapping.
     * 0.0 = undeclared: the WebUI then falls back to 1 for integer-ish types and
     * 0.01 for floats. Never used for validation — any value inside the range is
     * legal, the step only drives the nudge buttons.
     */
    val step: Double = 0.0
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
            OptionType.COLOR -> {
                val v = (value as? Number)?.toInt() ?: return false
                v in 0..COLOR_MAX
            }
        }
    }

    /**
     * Human-readable list of choices for this option (null if not a choice-like type).
     */
    fun displayChoices(): List<String>? = choices?.toList()

    /**
     * The step the WebUI should nudge by: the declared one, else a type default.
     * Returns null for options that are not numeric.
     */
    fun effectiveStep(): Double? = when (type) {
        OptionType.FLOAT -> if (step > 0.0) step else 0.01
        OptionType.INT, OptionType.PROBABILITY -> if (step > 0.0) step else 1.0
        else -> null
    }

    /**
     * Decimal places this option's declared step implies: 0.001 → 3, 0.01 → 2, 1.0 → 0.
     * Null for the non-numeric types.
     *
     * This is the single precision rule behind B2: the WebUI formats, snaps its +/- nudge and
     * saves with it ([step]/[decimals] are published in the option JSON) and
     * `HUD.autoHudValue` formats the derived module value with [formatNumber]. Before this they
     * were two independent formattings — the panel rounded every float to one decimal, the HUD
     * published the raw `Float.toString()` — which is exactly how the two could disagree.
     */
    fun decimals(): Int? {
        val effective = effectiveStep() ?: return null
        if (effective <= 0.0) return 0
        var scaled = effective
        var digits = 0
        while (digits < 6 && kotlin.math.abs(scaled - kotlin.math.round(scaled)) > 1e-9) {
            scaled *= 10.0
            digits++
        }
        return digits
    }

    /**
     * [value] rendered at this option's declared precision, locale-independent (a comma decimal
     * separator would break both the panel's parse and the config file's JSON).
     */
    fun formatNumber(value: Number): String {
        val decimals = decimals() ?: return value.toString()
        if (decimals <= 0) return value.toLong().toString()
        return String.format(java.util.Locale.ROOT, "%.${decimals}f", value.toDouble())
    }

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
