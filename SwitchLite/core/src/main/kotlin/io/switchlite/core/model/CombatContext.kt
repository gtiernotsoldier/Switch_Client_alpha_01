package io.switchlite.core.model

/**
 * Combat context snapshot containing relationship data between player and target.
 * Pure data class, no Minecraft dependencies.
 */
data class CombatContext(
    val playerState: PlayerState,
    val targetState: TargetState?,
    val distance: Float,
    val angleDiff: Float,
    val isTargetVisible: Boolean,
    val ticksInCombat: Long,
    val lastAttackTick: Long
)
