package io.switchlite.adapter.fabric.v1_21

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.IEventBridge
import io.switchlite.core.model.*
import io.switchlite.core.util.Vec2
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext
import net.minecraft.client.MinecraftClient

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

        return EventBridge.onVelocityPacket(ctx)
    }

    /**
     * Process tick event from Fabric event system.
     * Called by FabricBootstrap on client tick.
     */
    fun onTick() {
        val player = FabricStateExtractor.extractPlayerState()
        val targetId = FabricStateExtractor.getCurrentTargetId()
        val target = if (targetId != null) FabricStateExtractor.extractTargetState(targetId) else null

        EventBridge.onTick(player, target)
    }
}
