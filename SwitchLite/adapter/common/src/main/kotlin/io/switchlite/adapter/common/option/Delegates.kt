package io.switchlite.adapter.common.option

import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.option.ProbabilityOption
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Trigger options delegate builder.
 */
fun triggerOptions(name: String, builder: TriggerOptions.Builder.() -> Unit): ReadOnlyProperty<Any?, TriggerOptions> {
    val options = TriggerOptions.Builder().apply(builder).build()
    return ReadOnlyProperty { _, _ -> options }
}

/**
 * Probability option delegate.
 */
fun probability(name: String, default: Int, range: IntRange): ReadOnlyProperty<Any?, ProbabilityOption> {
    require(default in range) { "Default value must be in range $range" }
    return ReadOnlyProperty { _, _ -> ProbabilityOption(default) }
}
