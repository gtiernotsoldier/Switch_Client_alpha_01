package io.switchlite.core.model

import io.switchlite.core.util.Vec3

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
) {
    companion object {
        val EMPTY = CombatContext(
            playerState = PlayerState.EMPTY,
            targetState = null,
            distance = 0f,
            angleDiff = 0f,
            isTargetVisible = false,
            ticksInCombat = 0,
            lastAttackTick = 0
        )
    }
}
