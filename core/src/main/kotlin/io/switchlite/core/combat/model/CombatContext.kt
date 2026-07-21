package io.switchlite.core.combat.model

/**
 * Combat context containing all necessary state for strategy execution.
 */
data class CombatContext(
    val player: PlayerState,
    val target: TargetState?,
    val currentTick: Long,
    val lastAttackTick: Long,
    val consecutiveHits: Int
)
