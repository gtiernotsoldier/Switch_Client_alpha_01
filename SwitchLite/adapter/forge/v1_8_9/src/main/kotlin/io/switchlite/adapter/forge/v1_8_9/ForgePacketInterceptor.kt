package io.switchlite.adapter.forge.v1_8_9

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import net.minecraft.client.Minecraft
import net.minecraft.client.network.NetHandlerPlayClient
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import io.switchlite.core.logging.CoreLogger

/**
 * Netty channel handler that intercepts incoming velocity-related packets
 * BEFORE they reach NetHandlerPlayClient (and thus before motion is applied).
 *
 * Injection point: inserted into the NetworkManager pipeline by the Java Agent's
 * Transformer (hooks NetworkManager constructor or channelActive).
 *
 * Architecture:
 * - This class lives in the adapter layer (has MC imports).
 * - It calls ForgeEventBridge.onVelocityPacket() which builds a VelocityContext
 *   and passes it through EventBridge → Velocity module → Core strategy.
 * - The module returns a PlatformCommand; this handler acts on it.
 *
 * Constitution §1 (Safety): only intercepts velocity packets, never modifies
 * outgoing packets or game state directly.
 */
object ForgePacketInterceptor : ChannelDuplexHandler() {

    private const val HANDLER_NAME = "switchlite_velocity"
    private var injected = false

    /**
     * Ensure the interceptor is injected. Safe to call every tick —
     * no-ops if already injected or if netHandler is not yet available.
     */
    fun ensureInjected() {
        if (injected) return
        inject()
    }

    /**
     * Inject this handler into the player's network pipeline.
     * Called by ForgeBootstrap at init and retried via ensureInjected() on tick.
     */
    fun inject() {
        val mc = Minecraft.getMinecraft()
        val netHandler: NetHandlerPlayClient = mc.netHandler ?: return
        val channel = try {
            val networkManager = getNetworkManager(netHandler) ?: return
            val channelField = networkManager.javaClass.getDeclaredField("channel")
            channelField.isAccessible = true
            channelField.get(networkManager) as? io.netty.channel.Channel
        } catch (e: Exception) {
            CoreLogger.error("[ForgePacketInterceptor] Failed to get channel: ${e.message}")
            null
        } ?: return

        val pipeline: ChannelPipeline = channel.pipeline()
        if (pipeline.get(HANDLER_NAME) != null) {
            injected = true
            return // already injected
        }

        pipeline.addBefore("packet_handler", HANDLER_NAME, this)
        injected = true
        CoreLogger.info("[ForgePacketInterceptor] Injected into network pipeline")
    }

    /**
     * Remove this handler from the pipeline.
     * Called on disconnect or client shutdown.
     */
    fun eject() {
        injected = false
        val mc = Minecraft.getMinecraft()
        val netHandler: NetHandlerPlayClient = mc.netHandler ?: return
        val channel = try {
            val networkManager = getNetworkManager(netHandler) ?: return
            val channelField = networkManager.javaClass.getDeclaredField("channel")
            channelField.isAccessible = true
            channelField.get(networkManager) as? io.netty.channel.Channel
        } catch (e: Exception) {
            null
        } ?: return

        val pipeline = channel.pipeline()
        if (pipeline.get(HANDLER_NAME) != null) {
            pipeline.remove(HANDLER_NAME)
            CoreLogger.info("[ForgePacketInterceptor] Removed from network pipeline")
        }
    }

    /**
     * Intercept incoming packets.
     * Only processes S12PacketEntityVelocity (knockback) and S27PacketExplosion.
     */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is S12PacketEntityVelocity -> {
                val mc = Minecraft.getMinecraft()
                val player = mc.thePlayer

                // Only intercept velocity packets for the local player
                if (player != null && msg.entityID == player.entityId) {
                    val command = ForgeEventBridge.onVelocityPacket(msg)

                    when (command) {
                        is io.switchlite.core.model.PlatformCommand.CancelPacket -> {
                            // Swallow the packet — motion will not be applied
                            return // do NOT call super.channelRead
                        }
                        is io.switchlite.core.model.PlatformCommand.ModifyMotion -> {
                            // Let the packet through, then override motion next tick
                            ForgeEventBridge.pendingMotion = command.motion
                        }
                        is io.switchlite.core.model.PlatformCommand.ClickBurst -> {
                            // Send attack packets to target, then swallow velocity packet
                            sendClickBurst(command.targetId, command.times)
                            return
                        }
                        else -> {
                            // Pass or NoOp — let original packet through
                        }
                    }
                }
            }
            is S27PacketExplosion -> {
                // Explosion packets also apply velocity — intercept similarly
                val command = ForgeEventBridge.onVelocityPacket(msg)
                when (command) {
                    is io.switchlite.core.model.PlatformCommand.CancelPacket -> {
                        return
                    }
                    is io.switchlite.core.model.PlatformCommand.ModifyMotion -> {
                        ForgeEventBridge.pendingMotion = command.motion
                    }
                    is io.switchlite.core.model.PlatformCommand.ClickBurst -> {
                        sendClickBurst(command.targetId, command.times)
                        return
                    }
                    else -> {}
                }
            }
        }

        // Forward to vanilla handler
        super.channelRead(ctx, msg)
    }

    /**
     * Send C02PacketUseEntity (ATTACK) to the target entity.
     * Called when Velocity module returns ClickBurst command.
     */
    private fun sendClickBurst(targetId: Int, times: Int) {
        val mc = Minecraft.getMinecraft()
        val netHandler = mc.netHandler ?: return
        val world = mc.theWorld ?: return
        val target = world.getEntityByID(targetId) ?: return

        repeat(times) {
            val packet = net.minecraft.network.play.client.C02PacketUseEntity(
                target,
                net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK
            )
            netHandler.addToSendQueue(packet)
        }
        CoreLogger.debug("[ForgePacketInterceptor] ClickBurst: $times attacks on entity $targetId")
    }

    /**
     * Reflectively obtain the NetworkManager from NetHandlerPlayClient.
     * In 1.8.9 MCP mappings: field_147302_e (INetHandler) → NetworkManager.
     */
    private fun getNetworkManager(netHandler: NetHandlerPlayClient): Any? {
        return try {
            // MCP 1.8.9: NetHandlerPlayClient extends NetHandlerPlayServer? No.
            // NetworkManager is stored in the channel's pipeline context.
            // Alternative: use MappingContext semantic key.
            io.switchlite.agent.MappingContext.getFieldValue(
                netHandler, "forge:netHandlerPlayClient_networkManager"
            )
        } catch (e: Exception) {
            // Fallback: scan fields for NetworkManager type
            for (field in netHandler.javaClass.declaredFields) {
                if (field.type.simpleName == "NetworkManager") {
                    field.isAccessible = true
                    return field.get(netHandler)
                }
            }
            null
        }
    }
}
