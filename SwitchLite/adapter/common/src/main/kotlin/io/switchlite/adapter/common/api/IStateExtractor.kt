package io.switchlite.adapter.common.api

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.CombatContext

/**
 * Platform-agnostic state extraction interface.
 * Implementations in Forge/Fabric use MappingContext to read game data.
 */
interface IStateExtractor {
    fun extractPlayerState(): PlayerState
    fun extractTargetState(entityId: Int): TargetState?
    fun extractCombatContext(): CombatContext
    fun getCurrentTargetId(): Int?
}
