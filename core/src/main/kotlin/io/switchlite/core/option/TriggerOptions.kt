package io.switchlite.core.option

/**
 * Trigger options for condition-based activation.
 * Used by all modules to determine when to activate.
 */
data class TriggerOptions(
    val onlyGround: Boolean = false,
    val onlyAir: Boolean = false,
    val onlyMove: Boolean = false,
    val onlyMoveForward: Boolean = false,
    val onlyMoveBackward: Boolean = false,
    val onlyWhenTargetGoesBack: Boolean = false,
    val onlyClick: Boolean = false,
    val onlyCurrentView: Boolean = false,
    val disabledInAir: Boolean = false,
    val onLook: Boolean = false,
    val minDistance: Float = 0f,
    val maxDistance: Float = Float.MAX_VALUE
)
