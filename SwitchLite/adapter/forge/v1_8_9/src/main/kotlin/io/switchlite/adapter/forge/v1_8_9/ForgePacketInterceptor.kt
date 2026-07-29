package io.switchlite.adapter.forge.v1_8_9

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.switchlite.core.logging.CoreLogger
import io.switchlite.agent.MappingContext

/**
 * Netty channel handler that intercepts incoming velocity-related packets.
 * Pure reflection — no MC/Forge compile dependencies.
 * io.netty is a regular Maven dependency (kept).
 */
object ForgePacketInterceptor : ChannelDuplexHandler() {

    private const val HANDLER_NAME = "switchlite_velocity"
    private var injected = false

    // Lazy class references
    private val s12PacketClass by lazy { Class.forName("net.minecraft.network.play.server.S12PacketEntityVelocity") }
    private val s27PacketClass by lazy { Class.forName("net.minecraft.network.play.server.S27PacketExplosion") }

    // Attack packet constructor (shared with ForgeEventBridge pattern)
    private val c02PacketClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity") }
    private val c02ActionClass by lazy { Class.forName("net.minecraft.network.play.client.C02PacketUseEntity\$Action") }
    private val attackAction by lazy { c02ActionClass.enumConstants.firstOrNull { it.toString() == "ATTACK" } }
    private val c02AttackConstructor by lazy {
        c02PacketClass.getConstructor(
            Class.forName("net.minecraft.entity.Entity"),
            c02ActionClass
        )
    }

    fun ensureInjected() {
        if (injected) return
        inject()
    }

    fun inject() {
        val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
            ?: return
        val netHandler = try { MappingContext.getFieldValue(mc, "forge:mc_netHandler") } catch (_: Exception) { null }
            ?: return
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
            return
        }

        pipeline.addBefore("packet_handler", HANDLER_NAME, this)
        injected = true
        CoreLogger.info("[ForgePacketInterceptor] Injected into network pipeline")
    }

    fun eject() {
        injected = false
        val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
            ?: return
        val netHandler = try { MappingContext.getFieldValue(mc, "forge:mc_netHandler") } catch (_: Exception) { null }
            ?: return
        val channel = try {
            val networkManager = getNetworkManager(netHandler) ?: return
            val channelField = networkManager.javaClass.getDeclaredField("channel")
            channelField.isAccessible = true
            channelField.get(networkManager) as? io.netty.channel.Channel
        } catch (_: Exception) { null } ?: return

        val pipeline = channel.pipeline()
        if (pipeline.get(HANDLER_NAME) != null) {
            pipeline.remove(HANDLER_NAME)
            CoreLogger.info("[ForgePacketInterceptor] Removed from network pipeline")
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (s12PacketClass.isInstance(msg)) {
            val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
            val player = try { MappingContext.getFieldValue(mc, "forge:mc_thePlayer") } catch (_: Exception) { null }
            if (player != null) {
                val packetEntityId = try {
                    MappingContext.getFieldValue(msg, "forge:S12PacketEntityVelocity_entityID") as? Int
                } catch (_: Exception) { null }
                val playerEntityId = try {
                    MappingContext.getFieldValue(player, "forge:entity_entityId") as? Int
                } catch (_: Exception) { null }
                if (packetEntityId != null && packetEntityId == playerEntityId) {
                    val command = ForgeEventBridge.onVelocityPacket(msg)
                    when (command) {
                        is io.switchlite.core.model.PlatformCommand.CancelPacket -> return
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
        } else if (s27PacketClass.isInstance(msg)) {
            val command = ForgeEventBridge.onVelocityPacket(msg)
            when (command) {
                is io.switchlite.core.model.PlatformCommand.CancelPacket -> return
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

        super.channelRead(ctx, msg)
    }

    private fun sendClickBurst(targetId: Int, times: Int) {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft") ?: return
            val world = MappingContext.getFieldValue(mc, "forge:mc_theWorld") ?: return
            val target = MappingContext.invokeMethod(world, "forge:world_getEntityByID", targetId) ?: return

            val packet = c02AttackConstructor.newInstance(target, attackAction)
            val sendQueue = try {
                val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return
                MappingContext.getFieldValue(player, "forge:player_sendQueue") ?: return
            } catch (_: Exception) { return }
            repeat(times) {
                MappingContext.invokeMethod(sendQueue, "forge:netHandler_addToSendQueue", packet)
            }
            CoreLogger.debug("[ForgePacketInterceptor] ClickBurst: $times attacks on entity $targetId")
        } catch (_: Exception) {}
    }

    private fun getNetworkManager(netHandler: Any): Any? {
        return try {
            MappingContext.getFieldValue(netHandler, "forge:netHandlerPlayClient_networkManager")
        } catch (_: Exception) {
            // Fallback: scan for NetworkManager field
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
