package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.IStateExtractor
import io.switchlite.core.model.*
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext

/**
 * Forge 1.8.9 state extractor — pure reflection, zero MC/Forge compile dependencies.
 * All Minecraft API access via MappingContext semantic keys or Class.forName().
 */
object ForgeStateExtractor : IStateExtractor {

    // Lazy class references (runtime only — no compile-time MC dependency)
    private val entityClass by lazy { Class.forName("net.minecraft.entity.Entity") }
    private val entityLivingBaseClass by lazy { Class.forName("net.minecraft.entity.EntityLivingBase") }
    private val entityPlayerSPClass by lazy { Class.forName("net.minecraft.client.entity.EntityPlayerSP") }
    private val itemSwordClass by lazy { Class.forName("net.minecraft.item.ItemSword") }
    private val itemAxeClass by lazy { Class.forName("net.minecraft.item.ItemAxe") }
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val vec3Class by lazy { Class.forName("net.minecraft.util.Vec3") }
    private val vec3Constructor by lazy {
        vec3Class.getConstructor(Double::class.java, Double::class.java, Double::class.java)
    }
    private val movingObjectTypeClass by lazy {
        Class.forName("net.minecraft.util.MovingObjectPosition\$MovingObjectType")
    }
    private val movingObjectTypeBlock by lazy {
        movingObjectTypeClass.enumConstants.firstOrNull { it.toString() == "BLOCK" }
    }
    private val movingObjectTypeEntity by lazy {
        movingObjectTypeClass.enumConstants.firstOrNull { it.toString() == "ENTITY" }
    }
    private val mouseIsButtonDown by lazy {
        mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType)
    }

    private const val MAX_TARGET_RANGE = 6.0

    private var combatStartTick: Long = -1
    private var lastAttackTick: Long = 0
    private var lastTrackedTargetId: Int = -1

    // Helpers
    private fun getMc(): Any? = try {
        MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
    } catch (_: Exception) { null }

    private fun getPlayer(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_thePlayer") }
    } catch (_: Exception) { null }

    private fun getWorld(): Any? = try {
        getMc()?.let { MappingContext.getFieldValue(it, "forge:mc_theWorld") }
    } catch (_: Exception) { null }

    private fun isMouseButtonDown(button: Int): Boolean = try {
        mouseIsButtonDown.invoke(null, button) as Boolean
    } catch (_: Exception) { false }

    /**
     * Resolve the display name of an entity.
     * 1.8.8/1.8.9: Entity.getDisplayName() (func_145748_c_) returns an
     * IChatComponent; the plain string is getFormattedText() (func_150254_d).
     */
    private fun entityDisplayName(entity: Any?): String? {
        if (entity == null) return null
        return try {
            val comp = MappingContext.invokeMethod(entity, "forge:entity_name")
            MappingContext.invokeMethod(comp, "forge:ichatComponent_formattedText") as? String
        } catch (_: Exception) { null }
    }

    override fun extractPlayerState(): PlayerState {
        val player = getPlayer() ?: return PlayerState.EMPTY

        val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0
        val rotationYaw = MappingContext.getFieldValue(player, "forge:player_rotationYaw") as? Float ?: 0f
        val rotationPitch = MappingContext.getFieldValue(player, "forge:player_rotationPitch") as? Float ?: 0f
        val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
        val isSprinting = MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false
        val hurtTime = MappingContext.getFieldValue(player, "forge:entity_hurtTime") as? Int ?: 0
        val maxHurtResistantTime = MappingContext.getFieldValue(player, "forge:entity_maxHurtResistantTime") as? Int ?: 10
        val hurtResistantTime = MappingContext.getFieldValue(player, "forge:entity_hurtResistantTime") as? Int ?: hurtTime
        val health = MappingContext.invokeMethod(player, "forge:entity_health") as? Float ?: 0f

        val moveForward = MappingContext.getFieldValue(player, "forge:entity_player_moveForward") as? Float ?: 0f
        val moveStrafe = MappingContext.getFieldValue(player, "forge:entity_player_moveStrafing") as? Float ?: 0f
        val isMoving = (motionX != 0.0 || motionZ != 0.0)
        val isMovingForward = moveForward > 0f

        val isAttackKeyDown = isMouseButtonDown(0)

        val isBlocking = MappingContext.invokeMethod(player, "forge:player_isBlocking") as? Boolean ?: false
        val isSneaking = MappingContext.invokeMethod(player, "forge:player_isSneaking") as? Boolean ?: false
        // Player is in a swing/attack animation when swingProgressInt > 0.
        val isSwinging = (MappingContext.getFieldValue(player, "forge:entityLivingBase_swingProgressInt") as? Int ?: 0) > 0
        val selectedSlot = try {
            val inventory = MappingContext.getFieldValue(player, "forge:mc_thePlayer")
                ?.let { MappingContext.getFieldValue(it, "forge:inventory_currentItem") } as? Int
            inventory ?: 0
        } catch (_: Exception) { 0 }

        val heldItem = MappingContext.invokeMethod(player, "forge:player_heldItem")
        val weaponType = classifyWeapon(
            try { MappingContext.getFieldValue(heldItem, "forge:itemStack_item") } catch (_: Exception) { null }
        )

        val mc = getMc()
        val isMining = try {
            val pc = MappingContext.getFieldValue(mc, "forge:mc_playerController")
            MappingContext.getFieldValue(pc, "forge:playerController_isHittingBlock") as? Boolean ?: false
        } catch (_: Exception) { false }

        val world = getWorld()
        val ticks = MappingContext.invokeMethod(world, "forge:world_worldTime") as? Long ?: 0L

        return PlayerState(
            name = entityDisplayName(player) ?: "",
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
            hurtResistantTime = hurtResistantTime,
            attackCooldownProgress = 1.0f,
            isBlocking = isBlocking,
            isUsingItem = false,
            // Whether the crosshair is currently on a viable entity (Raven: objectMouseOver.entityHit).
            isLookingAtTarget = getCrosshairTargetId() != null,
            isSwinging = isSwinging,
            isMining = isMining,
            isSneaking = isSneaking,
            selectedSlot = selectedSlot,
            weaponType = weaponType,
            isAttackKeyDown = isAttackKeyDown,
            ticks = ticks
        )
    }

    override fun extractTargetState(entityId: Int): TargetState? {
        val world = getWorld() ?: return null
        val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return null

        val posX = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: 0.0
        val posY = MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: 0.0
        val posZ = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: 0.0
        val motionX = MappingContext.getFieldValue(entity, "forge:entity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(entity, "forge:entity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(entity, "forge:entity_motionZ") as? Double ?: 0.0
        val health = MappingContext.invokeMethod(entity, "forge:entity_health") as? Float ?: 0f
        val hurtTime = MappingContext.getFieldValue(entity, "forge:entity_hurtTime") as? Int ?: 0
        val hurtResistantTime = MappingContext.getFieldValue(entity, "forge:entity_hurtResistantTime") as? Int ?: hurtTime

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

        val player = getPlayer() ?: return null
        val playerX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val playerY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
        val playerZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
        val dx = posX - playerX
        val dy = posY - playerY
        val dz = posZ - playerZ
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()

        val dirToTargetX = posX - playerX
        val dirToTargetZ = posZ - playerZ
        val dirLen = kotlin.math.sqrt(dirToTargetX * dirToTargetX + dirToTargetZ * dirToTargetZ)

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

        val entityName = entityDisplayName(entity) ?: ""

        return TargetState(
            entityId = entityId,
            name = entityName,
            position = Vec3(posX, posY, posZ),
            motionX = motionX,
            motionY = motionY,
            motionZ = motionZ,
            health = health,
            hurtTime = hurtTime,
            hurtResistantTime = hurtResistantTime,
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
        val currentTick = getWorld()?.let { MappingContext.invokeMethod(it, "forge:world_worldTime") as? Long } ?: 0L

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

        val angleDiff: Float = if (target != null) {
            val dx = target.position.x - player.position.x
            val dz = target.position.z - player.position.z
            val yawToTarget = (kotlin.math.atan2(-dx, dz) * (180.0 / kotlin.math.PI)).toFloat()
            var diff = player.rotation.yaw - yawToTarget
            diff = ((diff + 180f) % 360f + 360f) % 360f - 180f
            kotlin.math.abs(diff)
        } else 0f

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

    private fun checkTargetVisibility(entityId: Int): Boolean {
        val player = getPlayer() ?: return false
        val world = getWorld() ?: return false
        val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", entityId) ?: return false

        val entityPos = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: return false
        val entityPosY = MappingContext.getFieldValue(entity, "forge:entity_posY") as? Double ?: return false
        val entityPosZ = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: return false

        val playerX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val playerY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
        val playerZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
        val eyeHeight = MappingContext.invokeMethod(player, "forge:player_eyeHeight") as? Double ?: 0.62

        val eyeVec = vec3Constructor.newInstance(playerX, playerY + eyeHeight, playerZ)
        val targetVec = vec3Constructor.newInstance(entityPos, entityPosY + 0.9, entityPosZ)

        val result = MappingContext.invokeMethod(world, "forge:world_rayTraceBlocks", eyeVec, targetVec, false, true, false)
        return result == null
    }

    /**
     * The entity under the crosshair (objectMouseOver.entityHit) only, no fallback.
     * Mirrors Raven's `mc.objectMouseOver.entityHit` for modules acting on the hit target.
     */
    override fun getCrosshairTargetId(): Int? {
        val player = getPlayer() ?: return null
        val mc = getMc()

        val objMouseOver = try { MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver") } catch (_: Exception) { null }
        if (objMouseOver != null) {
            val typeOfHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit") } catch (_: Exception) { null }
            if (typeOfHit === movingObjectTypeEntity) {
                val entityHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_entityHit") } catch (_: Exception) { null }
                if (entityHit != null && isViableTarget(entityHit, player)) {
                    return MappingContext.getFieldValue(entityHit, "forge:entity_entityId") as? Int
                }
            }
        }
        return null
    }

    override fun getCurrentTargetId(): Int? {
        val player = getPlayer() ?: return null
        val world = getWorld() ?: return null
        val mc = getMc()

        // Priority 1: crosshair-targeted entity
        val objMouseOver = try { MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver") } catch (_: Exception) { null }
        if (objMouseOver != null) {
            val typeOfHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit") } catch (_: Exception) { null }
            if (typeOfHit === movingObjectTypeEntity) {
                val entityHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_entityHit") } catch (_: Exception) { null }
                if (entityHit != null && isViableTarget(entityHit, player)) {
                    return MappingContext.getFieldValue(entityHit, "forge:entity_entityId") as? Int
                }
            }
        }

        // Priority 2: nearest viable entity
        val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return null
        val playerX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
        val playerZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0

        var nearestEntity: Any? = null
        var nearestDistSq = MAX_TARGET_RANGE * MAX_TARGET_RANGE

        for (entity in loadedList) {
            if (!isViableTarget(entity, player)) continue
            val ex = MappingContext.getFieldValue(entity, "forge:entity_posX") as? Double ?: continue
            val ez = MappingContext.getFieldValue(entity, "forge:entity_posZ") as? Double ?: continue
            val distSq = (ex - playerX) * (ex - playerX) + (ez - playerZ) * (ez - playerZ)
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq
                nearestEntity = entity
            }
        }

        return nearestEntity?.let { MappingContext.getFieldValue(it, "forge:entity_entityId") as? Int }
    }

    private fun isViableTarget(entity: Any?, player: Any): Boolean {
        if (entity === player) return false
        if (!entityLivingBaseClass.isInstance(entity)) return false
        val isDead = try { MappingContext.getFieldValue(entity, "forge:entity_isDead") } catch (_: Exception) { true }
        if (isDead == true) return false
        val health = MappingContext.invokeMethod(entity, "forge:entity_health") as? Float ?: 0f
        if (health <= 0f) return false
        return true
    }

    private fun classifyWeapon(item: Any?): WeaponType {
        return when {
            itemSwordClass.isInstance(item) -> WeaponType.SWORD
            itemAxeClass.isInstance(item) -> WeaponType.AXE
            else -> WeaponType.OTHER
        }
    }
}
