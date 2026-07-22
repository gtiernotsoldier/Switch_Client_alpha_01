package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.IStateExtractor
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.Minecraft

/**
 * Forge 1.8.9 state extractor.
 * Extracts pure data snapshots from Minecraft objects via MappingContext.
 * Zero direct field access — all through semantic mapping keys.
 */
object ForgeStateExtractor : IStateExtractor {

    private val mc get() = Minecraft.getMinecraft()

    override fun extractPlayerState(): PlayerState {
        val player = mc.thePlayer ?: return PlayerState.EMPTY

        val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0
        val rotationYaw = MappingContext.getFieldValue(player, "forge:player_rotationYaw") as? Float ?: 0f
        val rotationPitch = MappingContext.getFieldValue(player, "forge:player_rotationPitch") as? Float ?: 0f
        val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
        val isSprinting = MappingContext.getFieldValue(player, "forge:player_isSprinting") as? Boolean ?: false
        val hurtTime = MappingContext.getFieldValue(player, "forge:entity_hurtTime") as? Int ?: 0
        val maxHurtResistantTime = MappingContext.getFieldValue(player, "forge:entity_maxHurtResistantTime") as? Int ?: 10
        val health = MappingContext.getFieldValue(player, "forge:entity_health") as? Float ?: 0f

        val moveForward = MappingContext.getFieldValue(player, "forge:player_moveForward") as? Float ?: 0f
        val moveStrafe = MappingContext.getFieldValue(player, "forge:player_moveStrafing") as? Float ?: 0f
        val isMoving = (motionX != 0.0 || motionZ != 0.0)
        val isMovingForward = moveForward > 0f

        return PlayerState(
            name = player.name ?: "",
            position = Vec3(posX, posY, posZ),
            rotation = Vec2(rotationYaw, rotationPitch),
            motionX = motionX,
            motionY = motionY,
            motionZ = motionZ,
            onGround = onGround,
            isMoving = isMoving,
            isMovingForward = isMovingForward,
            isSprinting = isSprinting,
            health = health,
            hurtTime = hurtTime,
            maxHurtResistantTime = maxHurtResistantTime,
            isBlocking = false, // TODO: detect via MappingContext
            isLookingAtTarget = false, // TODO: implement raytrace
            isMining = false, // TODO: detect via MappingContext
            ticks = mc.theWorld?.worldTime?.toLong() ?: 0L
        )
    }

    override fun extractTargetState(entityId: Int): TargetState? {
        val world = mc.theWorld ?: return null
        val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return null

        val posX = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(entity, "forge:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(entity, "forge:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(entity, "forge:entity_motionZ") as? Double ?: 0.0
        val health = MappingContext.getFieldValue(entity, "forge:entity_health") as? Float ?: 0f
        val hurtTime = MappingContext.getFieldValue(entity, "forge:entity_hurtTime") as? Int ?: 0

        // Extract bounding box
        val bb = MappingContext.getFieldValue(entity, "forge:entity_boundingBox")
        val hitbox = if (bb != null) {
            Hitbox(
                minX = MappingContext.getFieldValue(bb, "forge:bb_minX") as? Double ?: 0.0,
                minY = MappingContext.getFieldValue(bb, "forge:bb_minY") as? Double ?: 0.0,
                minZ = MappingContext.getFieldValue(bb, "forge:bb_minZ") as? Double ?: 0.0,
                maxX = MappingContext.getFieldValue(bb, "forge:bb_maxX") as? Double ?: 0.0,
                maxY = MappingContext.getFieldValue(bb, "forge:bb_maxY") as? Double ?: 0.0,
                maxZ = MappingContext.getFieldValue(bb, "forge:bb_maxZ") as? Double ?: 0.0
            )
        } else {
            Hitbox(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val player = mc.thePlayer ?: return null
        val playerX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val playerY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
        val playerZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
        val dx = posX - playerX
        val dy = posY - playerY
        val dz = posZ - playerZ
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()

        return TargetState(
            entityId = entityId,
            name = "",
            position = Vec3(posX, posY, posZ),
            motionX = motionX,
            motionY = motionY,
            motionZ = motionZ,
            health = health,
            hurtTime = hurtTime,
            isMovingBackward = motionX * motionX + motionZ * motionZ > 0 && false, // TODO
            isGoingBack = false, // TODO
            isMovingTowardsPlayer = false, // TODO
            distance = distance,
            hitbox = hitbox,
            id = entityId
        )
    }

    override fun extractCombatContext(): CombatContext {
        val player = extractPlayerState()
        val targetId = getCurrentTargetId()
        val target = if (targetId != null) extractTargetState(targetId) else null
        val distance = target?.distance ?: 0f

        return CombatContext(
            playerState = player,
            targetState = target,
            distance = distance,
            angleDiff = 0f, // TODO: calculate
            isTargetVisible = target != null, // TODO: raytrace
            ticksInCombat = 0, // TODO: track
            lastAttackTick = 0 // TODO: track
        )
    }

    override fun getCurrentTargetId(): Int? {
        // TODO: implement target selection (crosshair entity / nearest entity)
        return null
    }
}
