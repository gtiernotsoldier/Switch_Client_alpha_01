package io.switchlite.adapter.common.option

import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.option.ProbabilityOption
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Float option delegate with range validation.
 */
fun float(
    name: String,
    default: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = ""
): ReadOnlyProperty<Any?, Float> {
    require(default in range) { "Default value $default must be in range $range" }
    return ReadOnlyProperty { _, _ -> default }
}

/**
 * Int option delegate with range validation.
 */
fun int(
    name: String,
    default: Int,
    range: IntRange,
    unit: String = ""
): ReadOnlyProperty<Any?, Int> {
    require(default in range) { "Default value $default must be in range $range" }
    return ReadOnlyProperty { _, _ -> default }
}

/**
 * Boolean option delegate.
 */
fun boolean(
    name: String,
    default: Boolean
): ReadOnlyProperty<Any?, Boolean> {
    return ReadOnlyProperty { _, _ -> default }
}

/**
 * Enum option delegate.
 */
fun <T : Enum<T>> enum(
    name: String,
    default: T
): ReadOnlyProperty<Any?, T> {
    return ReadOnlyProperty { _, _ -> default }
}

/**
 * Choices option delegate — string-based selectable list.
 * The first element in [options] is the default value.
 * Strings double as GUI display names (no separate mapping needed).
 */
fun choices(
    name: String,
    options: Array<String>
): ReadOnlyProperty<Any?, String> {
    return ReadOnlyProperty { _, _ -> options[0] }
}

/**
 * Trigger options delegate builder.
 */
fun triggerOptions(
    name: String,
    builder: TriggerOptions.Builder.() -> Unit
): ReadOnlyProperty<Any?, TriggerOptions> {
    val options = TriggerOptions.Builder().apply(builder).build()
    return ReadOnlyProperty { _, _ -> options }
}

/**
 * Probability option delegate.
 */
fun probability(
    name: String,
    default: Int,
    range: IntRange
): ReadOnlyProperty<Any?, ProbabilityOption> {
    require(default in range) { "Default value must be in range $range" }
    return ReadOnlyProperty { _, _ -> ProbabilityOption(default) }
}
