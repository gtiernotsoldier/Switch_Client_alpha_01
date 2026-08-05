package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext

/**
 * Forge 1.8.9 event bridge — pure reflection, zero MC/Forge compile dependencies.
 * Translates module-layer requests into Minecraft state changes via MappingContext.
 */
object ForgeEventBridge : IEventBridge {

    // Lazy class references (runtime only)
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val mouseIsButtonDown by lazy {
        mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType)
    }
    private val entityLivingBaseClass by lazy { Class.forName("net.minecraft.entity.EntityLivingBase") }
    private val itemArmorClass by lazy { Class.forName("net.minecraft.item.ItemArmor") }
    private val armorMaterialClass by lazy { Class.forName("net.minecraft.item.ItemArmor\$ArmorMaterial") }
    private val clothMaterial by lazy { armorMaterialClass.enumConstants.firstOrNull { it.toString() == "CLOTH" } }

    // Packet class constructors (lazy, created once)
    private val c02PacketUseEntityClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity") }
    private val c02ActionClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity\$Action") }
    private val attackAction by lazy { c02ActionClass.enumConstants.firstOrNull { it.toString() == "ATTACK" } }
    private val c02AttackConstructor by lazy {
        c02PacketUseEntityClass.getConstructor(
            Class.forName("net.minecraft.entity.Entity"),
            c02ActionClass
        )
    }

    private val c0bPacketClass by lazy { Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction") }
    private val c0bActionClass by lazy { Class.forName("net.minecraft.network.play.client.C0BPacketEntityAction\$Action") }
    private val stopSprintingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "STOP_SPRINTING" } }
    private val startSprintingAction by lazy { c0bActionClass.enumConstants.firstOrNull { it.toString() == "START_SPRINTING" } }
    private val c0bConstructor by lazy {
        c0bPacketClass.getConstructor(
            Class.forName("net.minecraft.entity.Entity"),
            c0bActionClass
        )
    }

    private val c04PacketClass by lazy { Class.forName("net.minecraft.network.play.client.C03PacketPlayer\$C04PacketPlayerPosition") }
    private val c04Constructor by lazy {
        c04PacketClass.getConstructor(
            Double::class.java, Double::class.java, Double::class.java, Boolean::class.java
        )
    }

    private val c09PacketClass by lazy { Class.forName("net.minecraft.network.play.client.C09PacketHeldItemChange") }
    private val c09Constructor by lazy {
        c09PacketClass.getConstructor(Int::class.javaPrimitiveType)
    }

    private val blocksClass by lazy { Class.forName("net.minecraft.init.Blocks") }
    private val blocksAir by lazy { blocksClass.getField("AIR").get(null) }
    private val movingObjectTypeClass by lazy { Class.forName("net.minecraft.util.MovingObjectPosition\$MovingObjectType") }
    private val movingObjectTypeBlock by lazy { movingObjectTypeClass.enumConstants.firstOrNull { it.toString() == "BLOCK" } }

    // Cached field references for writes (avoid repeated getField lookups)
    private val keybindingPressedField by lazy { MappingContext.getField("forge:keybinding_pressed") }
    private val playerRotationYawField by lazy { MappingContext.getField("forge:player_rotationYaw") }
    private val playerRotationPitchField by lazy { MappingContext.getField("forge:player_rotationPitch") }
    private val playerHurtTimeField by lazy { MappingContext.getField("forge:entity_hurtTime") }
    private val mcLeftClickCounterField by lazy { MappingContext.getField("forge:mc_leftClickCounter") }
    private val mcRightClickDelayField by lazy { MappingContext.getField("forge:mc_rightClickDelayTimer") }
    private val entityRendererFovField by lazy { MappingContext.getField("forge:entityRenderer_fovModifierHand") }
    private val gsGammaField by lazy { MappingContext.getField("forge:gs_gammaSetting") }
    private val inventoryCurrentItemField by lazy { MappingContext.getField("forge:inventory_currentItem") }
    private val playerJumpTicksField by lazy { MappingContext.getField("forge:player_jumpTicks") }
    private val mcObjectMouseOverField by lazy { MappingContext.getField("forge:mc_objectMouseOver") }

    @Volatile
    var pendingMotion: Vec3? = null

    // ========== Helpers ==========
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

    private fun setKeyBindPressed(gsKey: String, pressed: Boolean) {
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
            val keyBind = MappingContext.getFieldValue(gs, gsKey) ?: return
            keybindingPressedField?.set(keyBind, pressed)
        } catch (_: Exception) {}
    }

    private fun sendPacket(packet: Any?) {
        try {
            val player = getPlayer() ?: return
            val sendQueue = MappingContext.getFieldValue(player, "forge:player_sendQueue") ?: return
            MappingContext.invokeMethod(sendQueue, "forge:netHandler_addToSendQueue", packet)
        } catch (_: Exception) {}
    }

    // ========== Registration ==========
    /**
     * 1.8.8/1.8.9 display name of an entity (getDisplayName -> getFormattedText).
     */
    private fun entityDisplayName(entity: Any): String? {
        return try {
            val comp = MappingContext.invokeMethod(entity, "forge:entity_name")
            MappingContext.invokeMethod(comp, "forge:ichatComponent_formattedText") as? String
        } catch (_: Exception) { null }
    }

    override fun registerListeners() {
        EventBridge.registerRotationSetter { rotation -> setPlayerRotation(rotation) }
        EventBridge.registerMotionApplier { motion -> applyMotion(motion) }

        EventBridge.registerSprintSetter { sprinting ->
            val player = getPlayer() ?: return@registerSprintSetter
            MappingContext.invokeMethod(player, "forge:player_setSprinting", sprinting)
        }

        EventBridge.registerReleaseUsingItemHandler {
            setKeyBindPressed("forge:gs_keyBindUseItem", false)
            try {
                val player = getPlayer() ?: return@registerReleaseUsingItemHandler
                MappingContext.invokeMethod(player, "forge:player_stopUsingItem")
            } catch (_: Exception) {}
        }

        EventBridge.registerPressUseItemHandler {
            setKeyBindPressed("forge:gs_keyBindUseItem", true)
        }

        EventBridge.registerPressForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", true) }
        EventBridge.registerReleaseForwardHandler { setKeyBindPressed("forge:gs_keyBindForward", false) }
        EventBridge.registerPressBackHandler { setKeyBindPressed("forge:gs_keyBindBack", true) }
        EventBridge.registerReleaseBackHandler { setKeyBindPressed("forge:gs_keyBindBack", false) }

        EventBridge.registerJumpHandler {
            try {
                val player = getPlayer() ?: return@registerJumpHandler
                MappingContext.invokeMethod(player, "forge:player_jump")
            } catch (_: Exception) {}
        }

        EventBridge.registerSprintResetHandler { mode ->
            val player = getPlayer() ?: return@registerSprintResetHandler
            when (mode) {
                "Nostop" -> {
                    try {
                        sendPacket(c0bConstructor.newInstance(player, stopSprintingAction))
                        sendPacket(c0bConstructor.newInstance(player, startSprintingAction))
                    } catch (_: Exception) {}
                }
                "Silent" -> {
                    try {
                        val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
                        val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
                        val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
                        val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
                        sendPacket(c04Constructor.newInstance(posX, posY, posZ, onGround))
                    } catch (_: Exception) {}
                }
            }
        }

        EventBridge.registerCancelAttackHandler {
            setKeyBindPressed("forge:gs_keyBindAttack", false)
        }

        EventBridge.registerResetClickDelayHandler {
            try {
                val mc = getMc() ?: return@registerResetClickDelayHandler
                mcLeftClickCounterField?.setInt(mc, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetJumpDelayHandler {
            try {
                val player = getPlayer() ?: return@registerResetJumpDelayHandler
                playerJumpTicksField?.setInt(player, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerReachSetter { distance ->
            val player = getPlayer() ?: return@registerReachSetter
            val world = getWorld() ?: return@registerReachSetter
            val targetId = ForgeStateExtractor.getCurrentTargetId() ?: return@registerReachSetter
            val entity = MappingContext.invokeMethod(world, "forge:world_getEntityByID", targetId) ?: return@registerReachSetter
            if (!entityLivingBaseClass.isInstance(entity)) return@registerReachSetter
            val isAlive = try { MappingContext.invokeMethod(entity, "forge:entityLivingBase_isEntityAlive") as? Boolean ?: false } catch (_: Exception) { false }
            if (!isAlive) return@registerReachSetter
            val dist = try {
                MappingContext.invokeMethod(player, "forge:entity_getDistanceToEntity", entity) as? Double ?: Double.MAX_VALUE
            } catch (_: Exception) { Double.MAX_VALUE }
            if (dist > distance) return@registerReachSetter
            try {
                val mopClass = Class.forName("net.minecraft.util.MovingObjectPosition")
                val mopCtor = mopClass.getConstructor(Class.forName("net.minecraft.entity.Entity"))
                mcObjectMouseOverField?.set(getMc(), mopCtor.newInstance(entity))
            } catch (_: Exception) {}
        }

        EventBridge.registerSwitchSlotHandler { slot ->
            try {
                val player = getPlayer() ?: return@registerSwitchSlotHandler
                inventoryCurrentItemField?.setInt(player, slot)
                sendPacket(c09Constructor.newInstance(slot))
            } catch (_: Exception) {}
        }

        EventBridge.registerGetBestSlotHandler {
            var bestSlot = -1
            var bestSpeed = 1.0f
            val player = getPlayer() ?: return@registerGetBestSlotHandler -1
            val mc = getMc()
            val objMouseOver = try { MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver") } catch (_: Exception) { null }
            if (objMouseOver == null) return@registerGetBestSlotHandler -1

            val typeOfHit = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit") } catch (_: Exception) { null }
            if (typeOfHit !== movingObjectTypeBlock) return@registerGetBestSlotHandler -1

            val blockPos = try { MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_blockPos") } catch (_: Exception) { null }
            val world = getWorld() ?: return@registerGetBestSlotHandler -1
            val blockState = try { MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos) } catch (_: Exception) { null }
            val block = try { MappingContext.invokeMethod(blockState, "forge:iblockstate_block") } catch (_: Exception) { null }

            val inventory = try { MappingContext.getFieldValue(player, "forge:player_inventory") } catch (_: Exception) { null }
            for (i in 0..8) {
                val stack = try { MappingContext.invokeMethod(inventory, "forge:inventory_getStackInSlot", i) } catch (_: Exception) { null }
                if (stack == null) continue
                val item = try { MappingContext.getFieldValue(stack, "forge:itemStack_item") } catch (_: Exception) { null }
                if (item == null || block == null) continue
                val speed = try { MappingContext.invokeMethod(item, "forge:item_getDigSpeed", stack, block) as? Float } catch (_: Exception) { null }
                if (speed != null && speed > bestSpeed) {
                    bestSpeed = speed
                    bestSlot = i
                }
            }
            if (bestSpeed > 1.0f) bestSlot else -1
        }

        EventBridge.registerPressSneakHandler { setKeyBindPressed("forge:gs_keyBindSneak", true) }
        EventBridge.registerReleaseSneakHandler { setKeyBindPressed("forge:gs_keyBindSneak", false) }
        EventBridge.registerEdgeDetector {
            val player = getPlayer() ?: return@registerEdgeDetector false
            val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
            if (!onGround) return@registerEdgeDetector false
            val world = getWorld() ?: return@registerEdgeDetector false
            val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
            val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
            val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
            try {
                val blockPosClass = Class.forName("net.minecraft.util.BlockPos")
                val blockPos = blockPosClass.getConstructor(Double::class.java, Double::class.java, Double::class.java)
                    .newInstance(posX, posY - 1.0, posZ)
                val bs = MappingContext.invokeMethod(world, "forge:world_getBlockState", blockPos)
                val b = MappingContext.invokeMethod(bs, "forge:iblockstate_block")
                b !== blocksAir
            } catch (_: Exception) { false }
        }

        EventBridge.registerRotationApplier { yaw, pitch ->
            try {
                val player = getPlayer() ?: return@registerRotationApplier
                playerRotationYawField?.setFloat(player, yaw)
                playerRotationPitchField?.setFloat(player, pitch)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetHurtCamHandler {
            try {
                val player = getPlayer() ?: return@registerResetHurtCamHandler
                playerHurtTimeField?.setInt(player, 0)
            } catch (_: Exception) {}
        }

        EventBridge.registerResetFovModifierHandler {
            try {
                val mc = getMc() ?: return@registerResetFovModifierHandler
                val er = MappingContext.getFieldValue(mc, "forge:mc_entityRenderer") ?: return@registerResetFovModifierHandler
                entityRendererFovField?.setFloat(er, 1.0f)
            } catch (_: Exception) {}
        }

        EventBridge.registerGammaSetter { gamma ->
            try {
                val mc = getMc() ?: return@registerGammaSetter
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return@registerGammaSetter
                gsGammaField?.setFloat(gs, gamma)
            } catch (_: Exception) {}
        }

        EventBridge.registerRightClickDelayHandler { ticks ->
            try {
                val mc = getMc() ?: return@registerRightClickDelayHandler
                mcRightClickDelayField?.setInt(mc, ticks)
            } catch (_: Exception) {}
        }

        EventBridge.registerScoreboardTeamChecker { name ->
            try {
                val world = getWorld() ?: return@registerScoreboardTeamChecker null
                val scoreboard = MappingContext.invokeMethod(world, "forge:world_scoreboard") ?: return@registerScoreboardTeamChecker null
                val teams = MappingContext.getFieldValue(scoreboard, "forge:scoreboard_teams") as? Collection<*> ?: return@registerScoreboardTeamChecker null
                for (team in teams) {
                    val members = MappingContext.getFieldValue(team, "forge:scorePlayerTeam_membershipCollection") as? Collection<*>
                    if (members != null && members.contains(name)) {
                        return@registerScoreboardTeamChecker MappingContext.getFieldValue(team, "forge:scorePlayerTeam_registeredName") as? String
                    }
                }
                null
            } catch (_: Exception) { null }
        }

        EventBridge.registerDisplayNameProvider { name ->
            try {
                val world = getWorld() ?: return@registerDisplayNameProvider name
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerDisplayNameProvider name
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        val displayName = MappingContext.invokeMethod(entity, "forge:entityLivingBase_displayName")
                        return@registerDisplayNameProvider MappingContext.invokeMethod(displayName, "forge:ichatComponent_formattedText") as? String ?: name
                    }
                }
                name
            } catch (_: Exception) { name }
        }

        EventBridge.registerArmorColorChecker { name ->
            try {
                val world = getWorld() ?: return@registerArmorColorChecker -1
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerArmorColorChecker -1
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName != name) continue
                    for (i in 1..4) {
                        val stack = try { MappingContext.invokeMethod(entity, "forge:entityLivingBase_getEquipmentInSlot", i) } catch (_: Exception) { null }
                            ?: continue
                        if (!itemArmorClass.isInstance(MappingContext.getFieldValue(stack, "forge:itemStack_item"))) continue
                        val hasTag = try { MappingContext.getFieldValue(stack, "forge:itemStack_hasTagCompound") as? Boolean } catch (_: Exception) { false }
                        if (hasTag != true) continue
                        val tagCompound = try { MappingContext.getFieldValue(stack, "forge:itemStack_tagCompound") } catch (_: Exception) { null } ?: continue
                        val hasKey = try { MappingContext.invokeMethod(tagCompound, "forge:nbtBase_hasKey", "display", 10) as? Boolean } catch (_: Exception) { false }
                        if (hasKey != true) continue
                        val display = try { MappingContext.invokeMethod(tagCompound, "forge:nbtBase_getCompoundTag", "display") } catch (_: Exception) { null } ?: continue
                        return@registerArmorColorChecker try { MappingContext.invokeMethod(display, "forge:nbtBase_getInteger", "color") as? Int } catch (_: Exception) { -1 } ?: -1
                    }
                }
                -1
            } catch (_: Exception) { -1 }
        }

        EventBridge.registerEntityTicksProvider { name ->
            try {
                val world = getWorld() ?: return@registerEntityTicksProvider 0
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerEntityTicksProvider 0
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        return@registerEntityTicksProvider MappingContext.getFieldValue(entity, "forge:entity_ticksExisted") as? Int ?: 0
                    }
                }
                0
            } catch (_: Exception) { 0 }
        }

        EventBridge.registerEntityOnGroundChecker { name ->
            try {
                val world = getWorld() ?: return@registerEntityOnGroundChecker false
                val loadedList = MappingContext.getFieldValue(world, "forge:world_loadedEntityList") as? List<*> ?: return@registerEntityOnGroundChecker false
                for (entity in loadedList) {
                    if (!entityLivingBaseClass.isInstance(entity)) continue
                    val entityName = entityDisplayName(entity) ?: continue
                    if (entityName == name) {
                        return@registerEntityOnGroundChecker MappingContext.getFieldValue(entity, "forge:entity_onGround") as? Boolean ?: false
                    }
                }
                false
            } catch (_: Exception) { false }
        }

        EventBridge.registerAttackTrigger {
            setKeyBindPressed("forge:gs_keyBindAttack", true)
        }
    }

    override fun unregisterListeners() {
        EventBridge.reset()
    }

    private fun setPlayerRotation(rotation: Vec2) {
        val player = getPlayer() ?: return
        try {
            playerRotationYawField?.setFloat(player, rotation.yaw)
            playerRotationPitchField?.setFloat(player, rotation.pitch)
        } catch (_: Exception) {}
    }

    private fun applyMotion(motion: Vec3) {
        val player = getPlayer() ?: return
        try {
            MappingContext.getField("forge:entity_motionX")?.setDouble(player, motion.x)
            MappingContext.getField("forge:entity_motionY")?.setDouble(player, motion.y)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, motion.z)
        } catch (_: Exception) {}
    }

    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        val rawX = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionX")
        val rawY = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionY")
        val rawZ = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionZ")

        val motionX: Double
        val motionY: Double
        val motionZ: Double

        when {
            rawX is Int -> {
                motionX = rawX / 8000.0
                motionY = (rawY as? Int ?: 0) / 8000.0
                motionZ = (rawZ as? Int ?: 0) / 8000.0
            }
            rawX is Double -> {
                motionX = rawX
                motionY = rawY as? Double ?: 0.0
                motionZ = rawZ as? Double ?: 0.0
            }
            rawX is Float -> {
                motionX = rawX.toDouble()
                motionY = (rawY as? Float ?: 0f).toDouble()
                motionZ = (rawZ as? Float ?: 0f).toDouble()
            }
            else -> {
                motionX = 0.0; motionY = 0.0; motionZ = 0.0
            }
        }

        val ctx = VelocityContext(
            originalMotion = Vec3(motionX, motionY, motionZ),
            player = player,
            target = target,
            packetHandle = packetHandle
        )

        EventBridge.notifyVelocityPacket(ctx)
        return EventBridge.onVelocityPacket(ctx)
    }

    fun onTick() {
        // Release synthetic attack key if physical mouse not held
        try {
            val mc = getMc() ?: return
            val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
            val keyBindAttack = MappingContext.getFieldValue(gs, "forge:gs_keyBindAttack") ?: return
            val pressed = keybindingPressedField?.getBoolean(keyBindAttack) ?: false
            if (pressed && !isMouseButtonDown(0)) {
                keybindingPressedField?.setBoolean(keyBindAttack, false)
            }
        } catch (_: Exception) {}

        // Apply pending motion override
        // Guard: only clear pendingMotion if the player is available to apply it.
        // If the player is null (e.g., just disconnected), preserve pendingMotion
        // for the next tick so the velocity modification is not silently lost.
        pendingMotion?.let { motion ->
            val player = getPlayer()
            if (player != null) {
                applyMotion(motion)
                pendingMotion = null
            }
        }

        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        EventBridge.onTick(player, target)
    }
}
