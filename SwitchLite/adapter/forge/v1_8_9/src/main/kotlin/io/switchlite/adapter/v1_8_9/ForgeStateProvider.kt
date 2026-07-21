package io.switchlite.adapter.v1_8_9

import io.switchlite.adapter.common.api.StateProvider
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.CombatContext
import io.switchlite.agent.MappingContext
import net.minecraft.client.Minecraft

object ForgeStateProvider : StateProvider {
    private val mc = Minecraft.getMinecraft()

    override fun extractPlayerState(): PlayerState {
        val player = mc.thePlayer ?: return PlayerState.EMPTY
        // Use MappingContext to read fields via MCP names
        val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as Double
        val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as Double
        val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as Double
        val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as Double
        val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as Double
        val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as Double
        val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as Boolean
        val isMovingForward = (MappingContext.getFieldValue(player, "forge:player_moveForward") as Float) > 0
        
        return PlayerState(posX, posY, posZ, motionX, motionY, motionZ, onGround, isMovingForward)
    }

    override fun extractTargetState(entityId: Int): TargetState? {
        val world = mc.theWorld ?: return null
        val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return null
        // Extract bounding box and motion similarly via MappingContext
        return TargetState.EMPTY // Simplified for brevity
    }

    override fun extractCombatContext(): CombatContext {
        return CombatContext.EMPTY
    }

    override fun getCurrentTargetId(): Int? {
        // Implement target selection logic here
        return null
    }
}
