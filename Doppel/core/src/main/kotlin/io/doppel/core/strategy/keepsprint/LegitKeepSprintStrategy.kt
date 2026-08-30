package io.doppel.core.strategy.keepsprint

/**
 * Legit mode strategy for KeepSprint.
 *
 * Instead of always restoring to a fixed percentage, this interpolates the keep
 * percentage based on the player's distance to the target. Closer targets get
 * a more conservative (lower) keep — looks natural to observers because a player
 * who just swung at point-blank would realistically slow down more.
 *
 * Core layer: zero platform dependencies, pure interpolation math.
 */
object LegitKeepSprintStrategy {

    /**
     * Calculate the keep percentage using linear interpolation based on distance.
     *
     * - distance <= [KeepSprintConfig.minReach] → [KeepSprintConfig.minKeep]
     * - distance >= [KeepSprintConfig.maxReach] → [KeepSprintConfig.maxKeep]
     * - in between → linear interpolation between minKeep and maxKeep
     * - no target (null distance) → fall back to [KeepSprintConfig.minKeep] (conservative)
     *
     * @param config  KeepSprint configuration snapshot.
     * @param distance Horizontal distance to target in blocks, or null if no target.
     * @return Keep percentage in [minKeep, maxKeep].
     */
    fun calculateKeep(config: KeepSprintConfig, distance: Float?): Float {
        val d = distance ?: return config.minKeep

        return when {
            d <= config.minReach -> config.minKeep
            d >= config.maxReach -> config.maxKeep
            else -> {
                val t = (d - config.minReach) / (config.maxReach - config.minReach)
                config.minKeep + t * (config.maxKeep - config.minKeep)
            }
        }
    }
}
