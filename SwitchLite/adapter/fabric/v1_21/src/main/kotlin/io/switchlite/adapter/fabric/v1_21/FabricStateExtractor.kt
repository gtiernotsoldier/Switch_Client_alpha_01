package io.switchlite.adapter.fabric.v1_21

import io.switchlite.adapter.common.api.IStateExtractor
import io.switchlite.core.model.*
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.*
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult

/**
 * Fabric 1.21 state extractor.
 * Extracts pure data snapshots from Minecraft objects via MappingContext.
 * Zero direct field access — all through semantic mapping keys.
 */
object FabricStateExtractor : IStateExtractor {

    private val mc get() = MinecraftClient.getInstance()

    /** Maximum target selection range in blocks. */
    private const val MAX_TARGET_RANGE = 6.0

    // ========== Combat Tracking State ==========
    private var combatStartTick: Long = -1
    private var lastAttackTick: Long = 0
    private var lastTrackedTargetId: Int = -1

    override fun extractPlayerState(): PlayerState {
        val player = mc.player ?: return PlayerState.EMPTY

        val posX = MappingContext.getFieldValue(player, "fabric:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(player, "fabric:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(player, "fabric:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(player, "fabric:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(player, "fabric:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(player, "fabric:entity_motionZ") as? Double ?: 0.0
        val rotationYaw = MappingContext.getFieldValue(player, "fabric:player_rotationYaw") as? Float ?: 0f
        val rotationPitch = MappingContext.getFieldValue(player, "fabric:player_rotationPitch") as? Float ?: 0f
        val onGround = MappingContext.getFieldValue(player, "fabric:entity_onGround") as? Boolean ?: false
        val isSprinting = MappingContext.getFieldValue(player, "fabric:player_isSprinting") as? Boolean ?: false
        val hurtTime = MappingContext.getFieldValue(player, "fabric:entity_hurtTime") as? Int ?: 0
        val maxHurtResistantTime = MappingContext.getFieldValue(player, "fabric:entity_maxHurtResistantTime") as? Int ?: 10
        val health = MappingContext.getFieldValue(player, "fabric:entity_health") as? Float ?: 0f

        val moveForward = MappingContext.getFieldValue(player, "fabric:player_moveForward") as? Float ?: 0f
        val moveStrafe = MappingContext.getFieldValue(player, "fabric:player_moveStrafing") as? Float ?: 0f
        val isMoving = (motionX != 0.0 || motionZ != 0.0)
        val isMovingForward = moveForward > 0f

        // Physical mouse left button via GLFW — decoupled from attackKey to avoid
        // self-pollution when AutoClicker sets keyBindAttack.pressed = true
        val isAttackKeyDown = try {
            val handle = mc.window.handle
            org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
        } catch (_: Exception) {
            false
        }

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
            attackCooldownProgress = player.getAttackCooldownProgress(0.0f),
            isBlocking = player.isBlocking, // kept for 1.8 backward compat
            isUsingItem = player.isUsingItem,
            isLookingAtTarget = false, // handled by ConditionChecker angle calc (method B)
            isMining = MappingContext.getFieldValue(mc.interactionManager, "fabric:interactionManager_isBreakingBlock") as? Boolean ?: false,
            weaponType = classifyWeapon(player.mainHandStack?.item),
            isAttackKeyDown = isAttackKeyDown,
            ticks = mc.world?.time ?: 0L
        )
    }

    override fun extractTargetState(entityId: Int): TargetState? {
        val world = mc.world ?: return null
        val entity = MappingContext.invokeMethod(world, "fabric:world_getEntityByID", entityId) ?: return null

        val posX = MappingContext.getFieldValue(entity, "fabric:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(entity, "fabric:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(entity, "fabric:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(entity, "fabric:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(entity, "fabric:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(entity, "fabric:entity_motionZ") as? Double ?: 0.0
        val health = MappingContext.getFieldValue(entity, "fabric:entity_health") as? Float ?: 0f
        val hurtTime = MappingContext.getFieldValue(entity, "fabric:entity_hurtTime") as? Int ?: 0

        // Extract bounding box
        val bb = MappingContext.getFieldValue(entity, "fabric:entity_boundingBox")
        val hitbox = if (bb != null) {
            Hitbox(
                minX = MappingContext.getFieldValue(bb, "fabric:bb_minX") as? Double ?: 0.0,
                minY = MappingContext.getFieldValue(bb, "fabric:bb_minY") as? Double ?: 0.0,
                minZ = MappingContext.getFieldValue(bb, "fabric:bb_minZ") as? Double ?: 0.0,
                maxX = MappingContext.getFieldValue(bb, "fabric:bb_maxX") as? Double ?: 0.0,
                maxY = MappingContext.getFieldValue(bb, "fabric:bb_maxY") as? Double ?: 0.0,
                maxZ = MappingContext.getFieldValue(bb, "fabric:bb_maxZ") as? Double ?: 0.0
            )
        } else {
            Hitbox(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val player = mc.player ?: return null
        val playerX = MappingContext.getFieldValue(player, "fabric:entity_posX") as? Double ?: 0.0
        val playerY = MappingContext.getFieldValue(player, "fabric:entity_posY") as? Double ?: 0.0
        val playerZ = MappingContext.getFieldValue(player, "fabric:entity_posZ") as? Double ?: 0.0
        val dx = posX - playerX
        val dy = posY - playerY
        val dz = posZ - playerZ
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()

        // Direction from player to target (horizontal)
        val dirToTargetX = posX - playerX
        val dirToTargetZ = posZ - playerZ
        val dirLen = kotlin.math.sqrt(dirToTargetX * dirToTargetX + dirToTargetZ * dirToTargetZ)

        // Target movement direction analysis:
        // Dot product of target motion with direction FROM player TO target.
        // Positive = target moving away from player (backward/retreating).
        // Negative = target moving toward player (approaching).
        val isMovingBackward: Boolean
        val isMovingTowardsPlayer: Boolean
        if (dirLen > 0.01 && (motionX != 0.0 || motionZ != 0.0)) {
            val dot = (motionX * dirToTargetX + motionZ * dirToTargetZ) / dirLen
            isMovingBackward = dot > 0.01
            isMovingTowardsPlayer = dot < -0.01
        } else {
            isMovingBackward = false
            isMovingTowardsPlayer = false
        }

        return TargetState(
            entityId = entityId,
            name = (entity as? LivingEntity)?.name?.string ?: "",
            position = Vec3(posX, posY, posZ),
            motionX = motionX,
            motionY = motionY,
            motionZ = motionZ,
            health = health,
            hurtTime = hurtTime,
            isMovingBackward = isMovingBackward,
            isGoingBack = isMovingBackward,
            isMovingTowardsPlayer = isMovingTowardsPlayer,
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
        val currentTick = mc.world?.time ?: 0L

        // Track combat start/end
        if (targetId != null) {
            if (lastTrackedTargetId != targetId) {
                combatStartTick = currentTick
                lastTrackedTargetId = targetId
            }
            if (player.isAttackKeyDown) {
                lastAttackTick = currentTick
            }
        } else {
            if (combatStartTick >= 0 && currentTick - combatStartTick > 60) {
                combatStartTick = -1
                lastTrackedTargetId = -1
            }
        }

        val ticksInCombat = if (combatStartTick >= 0) currentTick - combatStartTick else 0L

        // Angle difference: horizontal angle between player look direction and direction to target
        val angleDiff: Float = if (target != null) {
            val dx = target.position.x - player.position.x
            val dz = target.position.z - player.position.z
            val yawToTarget = (kotlin.math.atan2(-dx, dz) * (180.0 / kotlin.math.PI)).toFloat()
            var diff = player.rotation.yaw - yawToTarget
            diff = ((diff + 180f) % 360f + 360f) % 360f - 180f
            kotlin.math.abs(diff)
        } else 0f

        // Visibility: raytrace from player eye to target
        val isTargetVisible = if (targetId != null) {
            checkTargetVisibility(targetId)
        } else false

        return CombatContext(
            playerState = player,
            targetState = target,
            distance = distance,
            angleDiff = angleDiff,
            isTargetVisible = isTargetVisible,
            ticksInCombat = ticksInCombat,
            lastAttackTick = lastAttackTick
        )
    }

    /**
     * Check if the target entity is visible via raytrace.
     * Uses MC's world.raycast from player eye to target center.
     */
    private fun checkTargetVisibility(entityId: Int): Boolean {
        val player = mc.player ?: return false
        val world = mc.world ?: return false
        val entity = world.getEntityById(entityId) ?: return false

        val eyePos = net.minecraft.util.math.Vec3d(
            player.x,
            player.y + player.standingEyeHeight,
            player.z
        )
        val targetPos = net.minecraft.util.math.Vec3d(
            entity.x,
            entity.y + entity.height * 0.5,
            entity.z
        )

        val result = world.raycast(
            net.minecraft.world.RaycastContext(
                eyePos,
                targetPos,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                player
            )
        )
        return result.type == net.minecraft.util.hit.HitResult.Type.MISS
    }

    override fun getCurrentTargetId(): Int? {
        val player = mc.player ?: return null
        val world = mc.world ?: return null

        // Priority 1: crosshair-targeted entity (raytrace)
        val crosshair = mc.crosshairTarget
        if (crosshair != null && crosshair.type == HitResult.Type.ENTITY) {
            val entity = (crosshair as EntityHitResult).entity
            if (isViableTarget(entity, player)) {
                return entity.id
            }
        }

        // Priority 2: nearest viable entity within range
        var nearestEntity: Entity? = null
        var nearestDistSq = MAX_TARGET_RANGE * MAX_TARGET_RANGE

        for (entity in world.entities) {
            if (!isViableTarget(entity, player)) continue
            val dx = entity.x - player.x
            val dz = entity.z - player.z
            val distSq = dx * dx + dz * dz
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestEntity = entity
            }
        }

        return nearestEntity?.id
    }

    /**
 * Check if an entity is a valid attack target.
 * Viable = not self, alive, is LivingEntity (player or hostile mob).
 */
    private fun isViableTarget(entity: Entity, player: PlayerEntity): Boolean {
        if (entity === player) return false
        if (entity !is LivingEntity) return false
        if (!entity.isAlive) return false
        if (entity.isDead) return false
        // Accept players and mobs (hostile + passive both included;
        // PvP servers may want to filter teams, but that's a higher-level concern)
        return true
    }

    /**
     * Classify the held item into a [WeaponType] for weapon filter logic.
     * Fabric 1.21 uses the Item class hierarchy directly.
     */
    private fun classifyWeapon(item: Item?): WeaponType {
        return when (item) {
            is SwordItem -> WeaponType.SWORD
            is AxeItem -> WeaponType.AXE
            else -> WeaponType.OTHER
        }
    }
}
