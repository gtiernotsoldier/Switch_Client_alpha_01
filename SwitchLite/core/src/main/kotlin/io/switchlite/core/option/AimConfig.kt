package io.switchlite.core.option

import io.switchlite.core.option.AimMode
import io.switchlite.core.option.TriggerOptions

data class AimConfig(
    val enabled: Boolean = true,
    val mode: AimMode = AimMode.LEGIT,
    val range: ClosedFloatingPointRange<Float> = 3.0f..10.0f,
    val horizontalFov: Float = 120.0f,
    val verticalFov: Float = 60.0f,
    val aimSpeed: Int = 8,
    val smoothness: Float = 0.85f,
    val targetSelection: TargetSelection = TargetSelection.DISTANCE_NEAREST,
    val triggerOptions: TriggerOptions = TriggerOptions(),
    val noiseIntensity: Double = 0.05
)

enum class TargetSelection { DISTANCE_NEAREST, CROSSHAIR }
