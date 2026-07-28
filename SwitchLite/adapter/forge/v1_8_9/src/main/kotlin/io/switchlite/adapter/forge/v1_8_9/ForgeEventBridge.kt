package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.Minecraft
import org.lwjgl.input.Mouse

/**
 * Forge 1.8.9 event bridge implementation.
 * Translates Forge-specific events to common events via EventBridge singleton.
 */
object ForgeEventBridge : IEventBridge {

    private val mc get() = Minecraft.getMinecraft()

    /**
     * Pending motion override from velocity packet interception.
     * Applied on the next client tick after the packet passes through.
     */
    @Volatile
    var pendingMotion: Vec3? = null

    /**
     * Register Forge event listeners.
     * Called by ForgeBootstrap during initialization.
     */
    override fun registerListeners() {
        // Register rotation setter
        EventBridge.registerRotationSetter { rotation ->
            setPlayerRotation(rotation)
        }

        // Register motion applier
        EventBridge.registerMotionApplier { motion ->
            applyMotion(motion)
        }

        // Register sprint setter (KeepSprint)
        EventBridge.registerSprintSetter { sprinting ->
            val player = mc.thePlayer ?: return@registerSprintSetter
            player.isSprinting = sprinting
        }

        // Register releaseUsingItem handler (AutoClicker OnItemUse.STOP / AutoBlock)
        EventBridge.registerReleaseUsingItemHandler {
            mc.gameSettings.keyBindUseItem.pressed = false
            mc.thePlayer?.stopUsingItem()
        }

        // Register pressUseItem handler (AutoBlock — sword blocking)
        EventBridge.registerPressUseItemHandler {
            mc.gameSettings.keyBindUseItem.pressed = true
        }

        // Register forward key handlers (WTap)
        EventBridge.registerPressForwardHandler {
            mc.gameSettings.keyBindForward.pressed = true
        }
        EventBridge.registerReleaseForwardHandler {
            mc.gameSettings.keyBindForward.pressed = false
        }

        // Register back key handlers (STap)
        EventBridge.registerPressBackHandler {
            mc.gameSettings.keyBindBack.pressed = true
        }
        EventBridge.registerReleaseBackHandler {
            mc.gameSettings.keyBindBack.pressed = false
        }

        // Register jump handler (JumpReset)
        EventBridge.registerJumpHandler {
            mc.thePlayer?.jump()
        }

        // Register sprint reset handler (SprintReset — 1.8 exclusive)
        EventBridge.registerSprintResetHandler { mode ->
            val player = mc.thePlayer ?: return@registerSprintResetHandler
            when (mode) {
                "Nostop" -> {
                    player.sendQueue.addToSendQueue(
                        net.minecraft.network.play.client.C0BPacketEntityAction(
                            player, net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SPRINTING
                        )
                    )
                    player.sendQueue.addToSendQueue(
                        net.minecraft.network.play.client.C0BPacketEntityAction(
                            player, net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SPRINTING
                        )
                    )
                }
                "Silent" -> {
                    player.sendQueue.addToSendQueue(
                        net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition(
                            player.posX, player.posY, player.posZ, player.onGround
                        )
                    )
                }
            }
        }

        // Register cancel attack handler (HitSelect)
        EventBridge.registerCancelAttackHandler {
            mc.gameSettings.keyBindAttack.pressed = false
        }

        // Register click delay reset (DelayRemover — 1.8 exclusive)
        EventBridge.registerResetClickDelayHandler {
            mc.leftClickCounter = 0
        }

        // Register jump delay reset (NoJumpDelay — Movement)
        EventBridge.registerResetJumpDelayHandler {
            mc.thePlayer?.let { p ->
                try {
                    val f = p.javaClass.getDeclaredField("jumpTicks")
                    f.isAccessible = true
                    f.setInt(p, 0)
                } catch (_: Exception) {}
            }
        }

        // Register reach setter (Reach — 1.8 exclusive)
        EventBridge.registerReachSetter { distance ->
            val targetId = ForgeStateExtractor.getCurrentTargetId() ?: return@registerReachSetter
            val entity = mc.theWorld?.getEntityByID(targetId) ?: return@registerReachSetter
            if (entity !is net.minecraft.entity.EntityLivingBase || !entity.isEntityAlive) return@registerReachSetter
            val dist = mc.thePlayer?.getDistanceToEntity(entity) ?: return@registerReachSetter
            if (dist > distance) return@registerReachSetter
            mc.objectMouseOver = net.minecraft.util.MovingObjectPosition(entity)
        }

        // Register hotbar slot switching (AutoTool)
        EventBridge.registerSwitchSlotHandler { slot ->
            mc.thePlayer?.inventory?.currentItem = slot
            mc.thePlayer?.sendQueue?.addToSendQueue(
                net.minecraft.network.play.client.C09PacketHeldItemChange(slot)
            )
        }
        EventBridge.registerGetBestSlotHandler {
            var bestSlot = -1
            var bestSpeed = 1.0f
            val player = mc.thePlayer ?: return@registerGetBestSlotHandler -1
            val obj = mc.objectMouseOver ?: return@registerGetBestSlotHandler -1
            if (obj.typeOfHit != net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK) return@registerGetBestSlotHandler -1
            val block = mc.theWorld?.getBlockState(obj.blockPos)?.block ?: return@registerGetBestSlotHandler -1
            for (i in 0..8) {
                val stack = player.inventory.getStackInSlot(i) ?: continue
                val speed = stack.getItem().getDigSpeed(stack, block)
                if (speed > bestSpeed) {
                    bestSpeed = speed
                    bestSlot = i
                }
            }
            if (bestSpeed > 1.0f) bestSlot else -1
        }

        // Register sneak key handlers + edge detector (Eagle)
        EventBridge.registerPressSneakHandler {
            mc.gameSettings.keyBindSneak.pressed = true
        }
        EventBridge.registerReleaseSneakHandler {
            mc.gameSettings.keyBindSneak.pressed = false
        }
        EventBridge.registerEdgeDetector {
            val p = mc.thePlayer ?: return@registerEdgeDetector false
            if (!p.onGround) return@registerEdgeDetector false
            val world = mc.theWorld ?: return@registerEdgeDetector false
            val posBelow = net.minecraft.util.BlockPos(p.posX, p.posY - 1.0, p.posZ)
            world.getBlockState(posBelow).block == net.minecraft.init.Blocks.AIR
        }

        // Register rotation applier (BridgeAssist)
        EventBridge.registerRotationApplier { yaw, pitch ->
            mc.thePlayer?.run {
                rotationYaw = yaw
                rotationPitch = pitch
            }
        }

        // Register render overrides (NoHurtCam, NoFOV)
        EventBridge.registerResetHurtCamHandler {
            mc.thePlayer?.hurtTime = 0
        }
        EventBridge.registerResetFovModifierHandler {
            mc.entityRenderer.fovModifierHand = 1.0f
        }

        // Register gamma setter (Fullbright)
        EventBridge.registerGammaSetter { gamma ->
            mc.gameSettings.gammaSetting = gamma
        }

        // Register right-click delay (FastPlace)
        EventBridge.registerRightClickDelayHandler { ticks ->
            mc.rightClickDelayTimer = ticks
        }

        // Register team detection (Teams)
        EventBridge.registerScoreboardTeamChecker { name ->
            mc.theWorld?.scoreboard?.teams?.firstOrNull { team ->
                team.membershipCollection.func_96562_d(name) ?: false
            }?.registeredName
        }
        EventBridge.registerDisplayNameProvider { name ->
            mc.theWorld?.loadedEntityList?.find {
                it is net.minecraft.entity.EntityLivingBase && it.name == name
            }?.displayName?.formattedText ?: name
        }
        EventBridge.registerArmorColorChecker { name ->
            val entity = mc.theWorld?.loadedEntityList?.find {
                it is net.minecraft.entity.EntityLivingBase && it.name == name
            } as? net.minecraft.entity.EntityLivingBase ?: return@registerArmorColorChecker -1
            for (i in 1..4) {
                val stack = entity.getEquipmentInSlot(i) ?: continue
                if (stack.item !is net.minecraft.item.ItemArmor) continue
                val armor = stack.item as net.minecraft.item.ItemArmor
                if (armor.getArmorMaterial() != net.minecraft.item.ItemArmor.ArmorMaterial.CLOTH) continue
                if (stack.hasTagCompound() && stack.tagCompound.hasKey("display", 10)) {
                    val display = stack.tagCompound.getCompoundTag("display")
                    if (display.hasKey("color", 3)) return@registerArmorColorChecker display.getInteger("color")
                }
            }
            -1
        }

        // Register entity info (AntiBot)
        EventBridge.registerEntityTicksProvider { name ->
            val entity = mc.theWorld?.loadedEntityList?.find {
                it is net.minecraft.entity.EntityLivingBase && it.name == name
            } ?: return@registerEntityTicksProvider 0
            (entity as net.minecraft.entity.Entity).ticksExisted
        }
        EventBridge.registerEntityOnGroundChecker { name ->
            val entity = mc.theWorld?.loadedEntityList?.find {
                it is net.minecraft.entity.EntityLivingBase && it.name == name
            } as? net.minecraft.entity.EntityLivingBase ?: return@registerEntityOnGroundChecker false
            entity.onGround
        }

        // Register attack trigger (AutoClicker)
        // Uses the LWJGL input pipeline (keyBindAttack.pressed) rather than
        // sending C02 packets directly — required by client-side anti-cheat
        // input queue monitors.
        EventBridge.registerAttackTrigger {
            mc.gameSettings.keyBindAttack.pressed = true
        }
    }

    /**
     * Unregister Forge event listeners.
     */
    override fun unregisterListeners() {
        EventBridge.reset()
    }

    /**
     * Set player rotation via MappingContext.
     */
    private fun setPlayerRotation(rotation: Vec2) {
        val player = mc.thePlayer ?: return
        MappingContext.getFieldValue(player, "forge:player_rotationYaw")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setFloat(player, rotation.yaw)
            }
        }
        MappingContext.getFieldValue(player, "forge:player_rotationPitch")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setFloat(player, rotation.pitch)
            }
        }
    }

    /**
     * Apply motion to player via MappingContext.
     */
    private fun applyMotion(motion: Vec3) {
        val player = mc.thePlayer ?: return
        MappingContext.getFieldValue(player, "forge:entity_motionX")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.x)
            }
        }
        MappingContext.getFieldValue(player, "forge:entity_motionY")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.y)
            }
        }
        MappingContext.getFieldValue(player, "forge:entity_motionZ")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.z)
            }
        }
    }

    /**
     * Process velocity packet from Forge event system.
     * Called by ForgePacketInterceptor when S12PacketEntityVelocity or S27PacketExplosion is received.
     *
     * S12PacketEntityVelocity stores velocity as int (1/8000 block/tick).
     * We convert to block/tick doubles before passing to the module pipeline.
     */
    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        // S12PacketEntityVelocity: getMotionX/Y/Z() returns int (raw packet units)
        val rawX = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionX")
        val rawY = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionY")
        val rawZ = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionZ")

        val motionX: Double
        val motionY: Double
        val motionZ: Double

        when {
            // Int values from S12PacketEntityVelocity (divide by 8000)
            rawX is Int -> {
                motionX = rawX / 8000.0
                motionY = (rawY as? Int ?: 0) / 8000.0
                motionZ = (rawZ as? Int ?: 0) / 8000.0
            }
            // Double values from S27PacketExplosion (already in block/tick)
            rawX is Double -> {
                motionX = rawX
                motionY = rawY as? Double ?: 0.0
                motionZ = rawZ as? Double ?: 0.0
            }
            // Float fallback
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

        // Notify passive observers (JumpReset) before dispatching to active listener (Velocity)
        EventBridge.notifyVelocityPacket(ctx)

        return EventBridge.onVelocityPacket(ctx)
    }

    /**
     * Process tick event from Forge event system.
     * Called by ForgeBootstrap on ClientTickEvent.
     */
    fun onTick() {
        // Release synthetic attack key press (set by attack trigger)
        // Only release if the physical mouse button is NOT held,
        // to avoid interfering with real player clicks.
        if (mc.gameSettings.keyBindAttack.pressed && !Mouse.isButtonDown(0)) {
            mc.gameSettings.keyBindAttack.pressed = false
        }

        // Apply pending motion override from velocity interception
        pendingMotion?.let { motion ->
            applyMotion(motion)
            pendingMotion = null
        }

        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        EventBridge.onTick(player, target)
    }
}
