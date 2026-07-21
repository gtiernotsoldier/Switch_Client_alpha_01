package io.switchlite.core.option

/**
 * Shared trigger options for all modules
 * Defines common conditional activation rules
 */
data class TriggerOptions(
    val onlyGround: Boolean = false,
    val onlyAir: Boolean = false,
    val onlyMove: Boolean = false,
    val onlyMoveForward: Boolean = false,
    val onlyMoveBackward: Boolean = false,
    val onlyStrafe: Boolean = false,
    val onlyWhenTargetGoesBack: Boolean = false,
    val onlyWhenTargetApproaches: Boolean = false,
    val onLook: Boolean = false,
    val disabledInAir: Boolean = false,
    val minDistance: Float = 0f,
    val maxDistance: Float = Float.MAX_VALUE,
    val chance: Int = 100,
    val delayTicks: Int = 0,
    val delayMs: Int = 0
)
