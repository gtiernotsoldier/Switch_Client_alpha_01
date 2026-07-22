package io.switchlite.adapter.fabric.v1_21

import io.switchlite.adapter.common.api.IStateExtractor
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.CombatContext
import io.switchlite.agent.MappingContext
import net.minecraft.client.MinecraftClient

object FabricStateExtractor : IStateExtractor {
    private val mc = MinecraftClient.getInstance()

    override fun extractPlayerState(): PlayerState {
        val player = mc.player ?: return PlayerState.EMPTY
        val posX = MappingContext.getFieldValue(player, "fabric:entity_posX") as Double
        val posY = MappingContext.getFieldValue(player, "fabric:entity_posY") as Double
        val posZ = MappingContext.getFieldValue(player, "fabric:entity_posZ") as Double
        val motionX = MappingContext.getFieldValue(player, "fabric:entity_motionX") as Double
        val motionY = MappingContext.getFieldValue(player, "fabric:entity_motionY") as Double
        val motionZ = MappingContext.getFieldValue(player, "fabric:entity_motionZ") as Double
        val onGround = MappingContext.getFieldValue(player, "fabric:entity_onGround") as Boolean
        val isMovingForward = true // Simplified
        
        return PlayerState(posX, posY, posZ, motionX, motionY, motionZ, onGround, isMovingForward)
    }

    override fun extractTargetState(entityId: Int): TargetState? = null
    override fun extractCombatContext(): CombatContext = CombatContext.EMPTY
    override fun getCurrentTargetId(): Int? = null
}
