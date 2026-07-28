package io.switchlite.adapter.fabric.v1_21

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.MinecraftClient
import org.lwjgl.glfw.GLFW

/**
 * Fabric 1.21 event bridge implementation.
 * Translates Fabric-specific events to common events via EventBridge singleton.
 */
object FabricEventBridge : IEventBridge {

    private val mc get() = MinecraftClient.getInstance()

    /**
     * Register Fabric event listeners.
     * Called by FabricBootstrap during initialization.
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
            mc.player?.setSprinting(sprinting)
        }

        // Register releaseUsingItem handler (AutoClicker OnItemUse.STOP / AutoBlock)
        EventBridge.registerReleaseUsingItemHandler {
            mc.options.useKey.isPressed = false
            mc.player?.stopUsingItem()
        }

        // Register pressUseItem handler (AutoBlock — sword blocking, 1.8 only)
        EventBridge.registerPressUseItemHandler {
            mc.options.useKey.isPressed = true
        }

        // Register forward key handlers (WTap)
        EventBridge.registerPressForwardHandler {
            mc.options.forwardKey.isPressed = true
        }
        EventBridge.registerReleaseForwardHandler {
            mc.options.forwardKey.isPressed = false
        }

        // Register back key handlers (STap)
        EventBridge.registerPressBackHandler {
            mc.options.backKey.isPressed = true
        }
        EventBridge.registerReleaseBackHandler {
            mc.options.backKey.isPressed = false
        }

        // Register jump handler (JumpReset)
        EventBridge.registerJumpHandler {
            mc.player?.jump()
        }

        // Register sprint reset handler (SprintReset)
        EventBridge.registerSprintResetHandler { mode ->
            val player = mc.player ?: return@registerSprintResetHandler
            val network = player.networkHandler ?: return@registerSprintResetHandler
            when (mode) {
                "Nostop" -> {
                    network.sendPacket(
                        net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(
                            player, net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.STOP_SPRINTING
                        )
                    )
                    network.sendPacket(
                        net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(
                            player, net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.START_SPRINTING
                        )
                    )
                }
                "Silent" -> {
                    network.sendPacket(
                        net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.PositionAndOnGround(
                            player.x, player.y, player.z, player.isOnGround
                        )
                    )
                }
            }
        }

        // Register cancel attack handler (HitSelect)
        EventBridge.registerCancelAttackHandler {
            mc.options.attackKey.isPressed = false
        }

        // Register jump delay reset (NoJumpDelay — Movement)
        EventBridge.registerResetJumpDelayHandler {
            mc.player?.let { p ->
                try {
                    val f = p.javaClass.getDeclaredField("jumpingCooldown")
                    f.isAccessible = true
                    f.setInt(p, 0)
                } catch (_: Exception) {}
            }
        }

        // Register reach setter (Reach)
        EventBridge.registerReachSetter { distance ->
            val targetId = FabricStateExtractor.getCurrentTargetId() ?: return@registerReachSetter
            val entity = mc.world?.getEntityById(targetId) ?: return@registerReachSetter
            if (entity !is net.minecraft.entity.LivingEntity || !entity.isAlive) return@registerReachSetter
            val dist = mc.player?.distanceTo(entity) ?: return@registerReachSetter
            if (dist > distance) return@registerReachSetter
            mc.crosshairTarget = net.minecraft.util.hit.EntityHitResult(entity)
        }

        // Register hotbar slot switching (AutoTool)
        EventBridge.registerSwitchSlotHandler { slot ->
            mc.player?.inventory?.selectedSlot = slot
            mc.player?.networkHandler?.sendPacket(
                net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot)
            )
        }
        EventBridge.registerGetBestSlotHandler {
            var bestSlot = -1
            var bestSpeed = 1.0f
            val player = mc.player ?: return@registerGetBestSlotHandler -1
            val target = mc.crosshairTarget ?: return@registerGetBestSlotHandler -1
            if (target.type != net.minecraft.util.hit.HitResult.Type.BLOCK) return@registerGetBestSlotHandler -1
            val pos = (target as net.minecraft.util.hit.BlockHitResult).blockPos
            val state = mc.world?.getBlockState(pos) ?: return@registerGetBestSlotHandler -1
            for (i in 0..8) {
                val stack = player.inventory.getStack(i)
                if (stack.isEmpty) continue
                val speed = stack.getItem().getMiningSpeed(stack, state)
                if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i }
            }
            if (bestSpeed > 1.0f) bestSlot else -1
        }

        // Register sneak key handlers + edge detector (Eagle)
        EventBridge.registerPressSneakHandler {
            mc.options.sneakKey.isPressed = true
        }
        EventBridge.registerReleaseSneakHandler {
            mc.options.sneakKey.isPressed = false
        }
        EventBridge.registerEdgeDetector {
            val p = mc.player ?: return@registerEdgeDetector false
            if (!p.isOnGround) return@registerEdgeDetector false
            val world = mc.world ?: return@registerEdgeDetector false
            val posBelow = net.minecraft.util.math.BlockPos(
                p.x.toInt(), (p.y - 1.0).toInt(), p.z.toInt()
            )
            world.getBlockState(posBelow).isAir
        }

        // Register rotation applier (BridgeAssist)
        EventBridge.registerRotationApplier { y, p ->
            mc.player?.run {
                yaw = y
                pitch = p
            }
        }

        // Register attack trigger (AutoClicker)
        // Uses the input pipeline via options.attackKey.isPressed rather than
        // sending packets directly — required by client-side anti-cheat monitors.
        EventBridge.registerAttackTrigger {
            mc.options.attackKey.isPressed = true
        }
    }

    /**
     * Unregister Fabric event listeners.
     */
    override fun unregisterListeners() {
        EventBridge.reset()
    }

    /**
     * Set player rotation via MappingContext.
     */
    private fun setPlayerRotation(rotation: Vec2) {
        val player = mc.player ?: return
        MappingContext.getFieldValue(player, "fabric:player_rotationYaw")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setFloat(player, rotation.yaw)
            }
        }
        MappingContext.getFieldValue(player, "fabric:player_rotationPitch")?.let { field ->
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
        val player = mc.player ?: return
        MappingContext.getFieldValue(player, "fabric:entity_motionX")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.x)
            }
        }
        MappingContext.getFieldValue(player, "fabric:entity_motionY")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.y)
            }
        }
        MappingContext.getFieldValue(player, "fabric:entity_motionZ")?.let { field ->
            (field as? java.lang.reflect.Field)?.apply {
                isAccessible = true
                setDouble(player, motion.z)
            }
        }
    }

    /**
     * Process velocity packet from Fabric event system.
     * Called by FabricBootstrap when EntityVelocityUpdateS2CPacket is received.
     */
    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        val player = FabricStateExtractor.extractPlayerState()
        val targetId = FabricStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) FabricStateExtractor.extractTargetState(targetId) else null

        val motionX = MappingContext.getFieldValue(packetHandle, "fabric:velocity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(packetHandle, "fabric:velocity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(packetHandle, "fabric:velocity_motionZ") as? Double ?: 0.0

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
     * Process tick event from Fabric event system.
     * Called by FabricBootstrap on client tick.
     */
    fun onTick() {
        // Release synthetic attack key press (set by attack trigger).
        // Only release if the physical mouse button is NOT held,
        // to avoid interfering with real player clicks.
        val window = mc.window
        val physicalDown = window != null &&
            GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        if (mc.options.attackKey.isPressed && !physicalDown) {
            mc.options.attackKey.isPressed = false
        }

        val player = FabricStateExtractor.extractPlayerState()
        val targetId = FabricStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) FabricStateExtractor.extractTargetState(targetId) else null

        EventBridge.onTick(player, target)
    }
}
