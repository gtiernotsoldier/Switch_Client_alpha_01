package io.doppel.adapter.fabric.v1_21

import io.doppel.contract.logging.CoreLogger
import io.doppel.contract.model.PlatformCommand
import io.doppel.contract.util.Vec3
import io.doppel.contract.model.VelocityContext

/**
 * Fabric 1.21 velocity packet decision object — the s2c half of the agent's ASM packet
 * hooks. This file no longer documents a Mixin target: the adapter carries NO SpongePowered
 * Mixin anywhere, by ruling of the owner. Inbound interception is ASM in the agent.
 *
 * ASM hook target (intermediary names, resolved by the agent from the fabric mapping
 * table — key fabric:entityVelocityUpdateS2CPacket_apply, guarded by
 * verify_mappings_fabric.mjs):
 *
 *     net.minecraft.class_2743.method_11817   apply(Lnet.minecraft.class_2602;)V
 *     = EntityVelocityUpdateS2CPacket.apply(ClientPlayPacketListener)
 *
 * The agent's Transformer injects a reflective call to io.doppel.agent.PacketBridge
 * .onVelocityPacket(packet) at the HEAD of apply(); this object is registered as the s2c
 * decision point by FabricBootstrap (PacketBridge.install). Returning true makes the hook
 * RETURN early, so the packet is never applied — exactly the old listener-HEAD cancel, one
 * layer earlier in the same call chain. apply() is declared on the interface-mapped packet
 * class, which is why it is the safe hook target: listener implementations inherit the
 * intermediary name through tiny-remapper's inheritance handling.
 *
 * Fail-open direction: with no agent attached there is no hook and no call into this file
 * at all — vanilla behaviour. If the bridge calls but no decision resolves
 * (PacketBridge.onVelocityPacket returns FALSE for a missing handler or any adapter
 * throw), the packet passes through untouched. A defect in here must degrade to "the
 * Velocity module does nothing", never to "velocity updates go missing".
 */
object FabricVelocityInterceptor {

    /**
     * Pending motion override. Set when a ModifyMotion command is issued.
     * Applied on the next client tick by FabricBootstrap.
     */
    @Volatile
    var pendingMotion: Vec3? = null

    /**
     * Bridge entry point for the agent's ASM hook on class_2743.method_11817 (apply HEAD).
     *
     * The hook hands us the raw packet as Object — this side is the only one allowed to
     * name Minecraft types. Type-checks, extracts via the 1.21.11 API and delegates to the
     * decision logic below. Any packet other than an EntityVelocityUpdateS2CPacket is
     * pass-through (false); an unexpected throw degrades to pass-through as well, in
     * keeping with the bridge's fail-open contract.
     *
     * @param packet the S2C packet object as delivered by the hook
     * @return true = cancel (the hook returns before apply()'s body), false = apply normally
     */
    fun onVelocityPacket(packet: Any): Boolean {
        return try {
            if (packet !is net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket) return false
            // 1.21.11 API: getEntityId() returns int (method_11818) and getVelocity() returns
            // a Vec3d in blocks/tick (method_73085) — the 1.8.9 raw-int 1/8000-unit API is
            // gone, so there is no unit conversion here anymore.
            process(packet.entityId, packet.velocity.x, packet.velocity.y, packet.velocity.z)
        } catch (t: Throwable) {
            CoreLogger.debug("[FabricVelocityInterceptor] velocity handling swallowed: ${t.message}")
            false
        }
    }

    /**
     * Decide one incoming velocity packet. Called from [onVelocityPacket] with typed
     * values already in blocks/tick.
     *
     * @param packetEntityId The entity ID the velocity packet targets.
     * @param velocityX Velocity X in blocks/tick (Vec3d.x of packet.velocity).
     * @param velocityY Velocity Y in blocks/tick.
     * @param velocityZ Velocity Z in blocks/tick.
     * @return true if the packet should be CANCELLED (hook returns early), false to let it through.
     */
    fun process(packetEntityId: Int, velocityX: Double, velocityY: Double, velocityZ: Double): Boolean {
        val mc = net.minecraft.client.MinecraftClient.getInstance()
        val player = mc.player ?: return false

        // Only intercept packets targeting the local player
        if (packetEntityId != player.id) return false

        val command = FabricEventBridge.onVelocityPacket(
            FabricVelocityPacketHandle(velocityX, velocityY, velocityZ)
        )

        return when (command) {
            is PlatformCommand.CancelPacket -> {
                CoreLogger.debug("[FabricVelocityInterceptor] Packet cancelled")
                true // the ASM hook returns early — apply() never runs
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
 * Opaque handle wrapping the parsed velocity values, in blocks/tick already (the 1.21.11
 * packet API hands out a Vec3d; the 1.8.9 1/8000-raw-int conversion no longer applies).
 * Passed as packetHandle in VelocityContext for Delay mode cancellation tracking.
 */
data class FabricVelocityPacketHandle(
    val motionX: Double,
    val motionY: Double,
    val motionZ: Double
)
