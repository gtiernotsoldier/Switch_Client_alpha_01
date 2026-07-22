package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.Minecraft

/**
 * Forge 1.8.9 event bridge implementation.
 * Translates Forge-specific events to common events via EventBridge singleton.
 */
object ForgeEventBridge : IEventBridge {

    private val mc get() = Minecraft.getMinecraft()

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
     * Called by ForgeBootstrap when S12PacketEntityVelocity is received.
     */
    fun onVelocityPacket(packetHandle: Any): PlatformCommand {
        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        val motionX = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionX") as? Double ?: 0.0
        val motionY = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionY") as? Double ?: 0.0
        val motionZ = MappingContext.getFieldValue(packetHandle, "forge:velocity_motionZ") as? Double ?: 0.0

        val ctx = VelocityContext(
            originalMotion = Vec3(motionX, motionY, motionZ),
            player = player,
            target = target,
            packetHandle = packetHandle
        )

        return EventBridge.onVelocityPacket(ctx)
    }

    /**
     * Process tick event from Forge event system.
     * Called by ForgeBootstrap on ClientTickEvent.
     */
    fun onTick() {
        val player = ForgeStateExtractor.extractPlayerState()
        val targetId = ForgeStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null

        EventBridge.onTick(player, target)
    }
}
