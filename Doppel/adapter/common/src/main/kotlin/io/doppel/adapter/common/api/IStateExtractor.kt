package io.doppel.adapter.common.api

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.model.CombatContext

/**
 * Platform-agnostic state extraction interface.
 * Implementations in Forge/Fabric use MappingContext to read game data.
 */
interface IStateExtractor {
    fun extractPlayerState(): PlayerState
    fun extractTargetState(entityId: Int): TargetState?
    fun extractCombatContext(): CombatContext

    /**
     * The entity currently under the player's crosshair (MC objectMouseOver.entityHit),
     * or null if the crosshair is not on a viable target. No "nearest entity" fallback.
     * Used by modules that act on the target the player is actually hitting
     * (WTap/STap/AutoBlock/BlockHit/SuperKnockback), matching Raven's `objectMouseOver.entityHit`.
     */
    fun getCrosshairTargetId(): Int?

    /** The current combat target (crosshair first, else nearest). Used by aim-type modules. */
    fun getCurrentTargetId(): Int?
}
