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
    val onlyCurrentView: Boolean = false,
    val disableOnMine: Boolean = false,
    val onlyOnClick: Boolean = false,
    val disabledInAir: Boolean = false,
    val minDistance: Float = 0f,
    val maxDistance: Float = Float.MAX_VALUE,
    val chance: Int = 100,
    val delayTicks: Int = 0,
    val delayMs: Int = 0
) {
    class Builder {
        var onlyGround: Boolean = false
        var onlyAir: Boolean = false
        var onlyMove: Boolean = false
        var onlyMoveForward: Boolean = false
        var onlyMoveBackward: Boolean = false
        var onlyStrafe: Boolean = false
        var onlyWhenTargetGoesBack: Boolean = false
        var onlyWhenTargetApproaches: Boolean = false
        var onLook: Boolean = false
        var onlyCurrentView: Boolean = false
        var disableOnMine: Boolean = false
        var onlyOnClick: Boolean = false
        var disabledInAir: Boolean = false
        var minDistance: Float = 0f
        var maxDistance: Float = Float.MAX_VALUE
        var chance: Int = 100
        var delayTicks: Int = 0
        var delayMs: Int = 0

        fun build(): TriggerOptions = TriggerOptions(
            onlyGround = onlyGround,
            onlyAir = onlyAir,
            onlyMove = onlyMove,
            onlyMoveForward = onlyMoveForward,
            onlyMoveBackward = onlyMoveBackward,
            onlyStrafe = onlyStrafe,
            onlyWhenTargetGoesBack = onlyWhenTargetGoesBack,
            onlyWhenTargetApproaches = onlyWhenTargetApproaches,
            onLook = onLook,
            onlyCurrentView = onlyCurrentView,
            disableOnMine = disableOnMine,
            onlyOnClick = onlyOnClick,
            disabledInAir = disabledInAir,
            minDistance = minDistance,
            maxDistance = maxDistance,
            chance = chance,
            delayTicks = delayTicks,
            delayMs = delayMs
        )
    }
}
