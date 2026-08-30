package io.doppel.adapter.fabric.v1_21

import io.doppel.core.logging.CoreLogger
import io.doppel.core.model.PlatformCommand
import io.doppel.core.util.Vec3

/**
 * Mixin target specification for Fabric 1.21 velocity packet interception.
 *
 * This file documents the Mixin injection points. The actual Mixin class
 * must be written in Java (Mixins require Java source) and registered in
 * the mod's mixin config JSON.
 *
 * Mixin target:
 *   net.minecraft.client.network.ClientPlayNetworkHandler
 *   .onEntityVelocityUpdate(EntityVelocityUpdateS2CPacket)
 *
 * The Mixin @Injects at HEAD of onEntityVelocityUpdate, extracts motion,
 * calls FabricVelocityInterceptor.process(), and cancels if needed.
 *
 * Since Kotlin cannot produce Mixin annotations directly, this object provides
 * the logic that the Java Mixin class delegates to.
 */
object FabricVelocityInterceptor {

    /**
     * Pending motion override. Set when a ModifyMotion command is issued.
     * Applied on the next client tick by FabricBootstrap.
     */
    @Volatile
    var pendingMotion: Vec3? = null

    /**
     * Process an incoming EntityVelocityUpdateS2CPacket.
     * Called by the Java Mixin at HEAD of onEntityVelocityUpdate.
     *
     * @param packetEntityId The entity ID the velocity packet targets.
     * @param velocityX Raw velocity X (packet units: 1/8000 block/tick).
     * @param velocityY Raw velocity Y.
     * @param velocityZ Raw velocity Z.
     * @return true if the packet should be CANCELLED (swallowed), false to let it through.
     */
    fun process(packetEntityId: Int, velocityX: Int, velocityY: Int, velocityZ: Int): Boolean {
        val mc = net.minecraft.client.MinecraftClient.getInstance()
        val player = mc.player ?: return false

        // Only intercept packets targeting the local player
        if (packetEntityId != player.id) return false

        // Convert packet units to block/tick (divide by 8000)
        val motionX = velocityX / 8000.0
        val motionY = velocityY / 8000.0
        val motionZ = velocityZ / 8000.0

        val command = FabricEventBridge.onVelocityPacket(
            FabricVelocityPacketHandle(motionX, motionY, motionZ)
        )

        return when (command) {
            is PlatformCommand.CancelPacket -> {
                CoreLogger.debug("[FabricVelocityInterceptor] Packet cancelled")
                true // cancel the Mixin injection
            }
            is PlatformCommand.ModifyMotion -> {
                // Store for next-tick application
                pendingMotion = command.motion
                false // let packet through, motion overridden next tick
            }
            is PlatformCommand.Pass -> false
            is PlatformCommand.NoOp -> false
            is PlatformCommand.ClickBurst -> false
        }
    }

    /**
     * Apply pending motion override. Called by FabricBootstrap on each client tick.
     */
    fun applyPendingMotion() {
        val motion = pendingMotion ?: return
        pendingMotion = null

        val mc = net.minecraft.client.MinecraftClient.getInstance()
        val player = mc.player ?: return

        player.setVelocity(motion.x, motion.y, motion.z)
    }
}

/**
 * Opaque handle wrapping the parsed velocity values.
 * Passed as packetHandle in VelocityContext for Delay mode cancellation tracking.
 */
data class FabricVelocityPacketHandle(
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double
)
