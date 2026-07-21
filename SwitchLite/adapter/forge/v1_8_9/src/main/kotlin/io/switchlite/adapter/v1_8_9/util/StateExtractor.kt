package io.switchlite.adapter.v1_8_9.util

import io.switchlite.agent.MappingContext
import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3

object StateExtractor {

    fun extractPlayerState(player: Any): PlayerState {
        val posX = MappingContext.resolveAndInvoke("forge:entity_getPosX", player) as Double
        val posY = MappingContext.resolveAndInvoke("forge:entity_getPosY", player) as Double
        val posZ = MappingContext.resolveAndInvoke("forge:entity_getPosZ", player) as Double
        val motionX = MappingContext.resolveAndInvoke("forge:entity_getMotionX", player) as Double
        val motionY = MappingContext.resolveAndInvoke("forge:entity_getMotionY", player) as Double
        val motionZ = MappingContext.resolveAndInvoke("forge:entity_getMotionZ", player) as Double
        val onGround = MappingContext.resolveAndInvoke("forge:entity_isOnGround", player) as Boolean
        val isMoving = MappingContext.resolveAndInvoke("forge:player_isMovingForward", player) as Boolean

        return PlayerState(
            position = Vec3(posX, posY, posZ),
            velocity = Vec3(motionX, motionY, motionZ),
            onGround = onGround,
            isMovingForward = isMoving,
            health = 20f, // Would extract from entity in production
            isLiving = true
        )
    }

    fun extractTargetState(target: Any, playerPos: Vec3): TargetState {
        val entityId = MappingContext.resolveAndInvoke("forge:entity_getEntityId", target) as Int
        val posX = MappingContext.resolveAndInvoke("forge:entity_getPosX", target) as Double
        val posY = MappingContext.resolveAndInvoke("forge:entity_getPosY", target) as Double
        val posZ = MappingContext.resolveAndInvoke("forge:entity_getPosZ", target) as Double
        val motionX = MappingContext.resolveAndInvoke("forge:entity_getMotionX", target) as Double
        val motionY = MappingContext.resolveAndInvoke("forge:entity_getMotionY", target) as Double
        val motionZ = MappingContext.resolveAndInvoke("forge:entity_getMotionZ", target) as Double
        
        val distance = Vec3(posX, posY, posZ).distanceTo(playerPos)
        
        // Simplified hitbox - would use actual bounding box in production
        val hitboxYaw = -45f..45f
        val hitboxPitch = -45f..45f

        return TargetState(
            entityId = entityId,
            position = Vec3(posX, posY, posZ),
            velocity = Vec3(motionX, motionY, motionZ),
            health = 20f,
            isLiving = true,
            hitboxYaw = hitboxYaw,
            hitboxPitch = hitboxPitch,
            distanceToPlayer = distance
        )
    }

    fun extractRotation(player: Any): Vec2 {
        val yaw = MappingContext.resolveAndInvoke("forge:player_rotationYaw", player) as Float
        val pitch = MappingContext.resolveAndInvoke("forge:player_rotationPitch", player) as Float
        return Vec2(yaw, pitch)
    }

    fun applyRotation(player: Any, rotation: Vec2) {
        MappingContext.resolveAndSet("forge:player_rotationYaw", player, rotation.yaw)
        MappingContext.resolveAndSet("forge:player_rotationPitch", player, rotation.pitch)
    }
}
