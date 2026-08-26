package io.switchlite.adapter.forge.v1_8_9

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.logging.CoreLogger
import io.switchlite.core.util.Vec3
import io.switchlite.agent.MappingContext

/**
 * Netty channel handler that intercepts incoming velocity-related packets.
 * Pure reflection — no MC/Forge compile dependencies.
 * io.netty is a regular Maven dependency (kept).
 */
object ForgePacketInterceptor : ChannelDuplexHandler() {

    private const val HANDLER_NAME = "switchlite_velocity"
    private var injected = false

    /** Diagnostic counters for S12 interception probes. */
    private var s12Diag = 0
    private var s12MismatchDiag = 0
    private var ensureDiag = 0

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
        // PROBE: confirm this is actually called and what injected/mc read (INFO level — visible).
        if (++ensureDiag % 60 == 0) {
            CoreLogger.info("[ForgePacketInterceptor] ensureInjected called, injected=$injected")
        }
        inject()
    }

    fun inject() {
        val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
        if (mc == null) {
            CoreLogger.info("[ForgePacketInterceptor] inject: mc_getMinecraft returned null")
            return
        }
        // netHandler = mc.thePlayer.sendQueue (EntityPlayerSP.sendQueue, field_71174_a — the same
        // verified path used for sending packets elsewhere). mc_netHandler (field_71453_ak) reads
        // null at runtime, so use the player's sendQueue instead.
        val player = try { MappingContext.getFieldValue(mc, "forge:mc_thePlayer") } catch (_: Exception) { null }
        if (player == null) {
            CoreLogger.info("[ForgePacketInterceptor] inject: mc_thePlayer is null (not in world yet)")
            return
        }
        val netHandler = try { MappingContext.getFieldValue(player, "forge:player_sendQueue") } catch (_: Exception) { null }
        if (netHandler == null) {
            CoreLogger.info("[ForgePacketInterceptor] inject: player.sendQueue is null (not in world yet)")
            return
        }
        val networkManager = getNetworkManager(netHandler)
        if (networkManager == null) {
            CoreLogger.error("[ForgePacketInterceptor] inject: getNetworkManager returned null for netHandler=$netHandler (class=${netHandler.javaClass.name})")
            return
        }
        val channel = resolveChannelField(networkManager)
        if (channel == null) {
            CoreLogger.error("[ForgePacketInterceptor] inject: resolveChannelField failed for ${networkManager.javaClass.name} (fields=${networkManager.javaClass.declaredFields.map { it.name + ":" + it.type.simpleName }})")
            return
        }

        val pipeline: ChannelPipeline = channel.pipeline()
        if (pipeline.get(HANDLER_NAME) != null) {
            injected = true
            return
        }

        // CRITICAL: pipeline mutation must run on the Netty event-loop thread. Calling
        // pipeline.addBefore() from the Agent background thread throws IllegalStateException
        // ("event executor terminated" / not the event loop), which the tick loop's catch
        // silently swallows — the interceptor silently never installs. Dispatch to the event loop.
        try {
            channel.eventLoop().execute {
                try {
                    val p = channel.pipeline()
                    if (p.get(HANDLER_NAME) == null) {
                        p.addBefore("packet_handler", HANDLER_NAME, this)
                    }
                    injected = true
                    CoreLogger.info("[ForgePacketInterceptor] Injected into network pipeline (event loop)")
                } catch (e: Exception) {
                    CoreLogger.error("[ForgePacketInterceptor] event-loop inject failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            CoreLogger.error("[ForgePacketInterceptor] eventLoop().execute failed: ${e.message}")
        }
    }

    fun eject() {
        injected = false
        val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
            ?: return
        val player = try { MappingContext.getFieldValue(mc, "forge:mc_thePlayer") } catch (_: Exception) { null }
            ?: return
        val netHandler = try { MappingContext.getFieldValue(player, "forge:player_sendQueue") } catch (_: Exception) { null }
            ?: return
        val channel = try {
            val networkManager = getNetworkManager(netHandler) ?: return
            resolveChannelField(networkManager)
        } catch (_: Exception) { null } ?: return

        val pipeline = channel.pipeline()
        if (pipeline.get(HANDLER_NAME) != null) {
            pipeline.remove(HANDLER_NAME)
            CoreLogger.info("[ForgePacketInterceptor] Removed from network pipeline")
        }
    }

    /**
     * Resolve the Netty Channel field on a NetworkManager. The field NAME is obfuscated at runtime
     * (SRG), so matching by name ("channel") throws NoSuchFieldException — the root cause of the
     * interceptor never injecting. Match by TYPE instead (io.netty.channel.Channel), which is not
     * obfuscated. Tries the direct-name first for deobfuscated environments, then scans by type.
     */
    private fun resolveChannelField(networkManager: Any): io.netty.channel.Channel? {
        val clazz = networkManager.javaClass
        // Fast path: a field literally named "channel" (deobf / dev env).
        try {
            val f = clazz.getDeclaredField("channel")
            f.isAccessible = true
            val v = f.get(networkManager)
            if (v is io.netty.channel.Channel) return v
        } catch (_: Exception) {}
        // Obfuscated runtime: scan all fields for one whose type is Channel.
        for (f in clazz.declaredFields) {
            if (io.netty.channel.Channel::class.java.isAssignableFrom(f.type)) {
                f.isAccessible = true
                try {
                    val v = f.get(networkManager)
                    if (v is io.netty.channel.Channel) return v
                } catch (_: Exception) {}
            }
        }
        return null
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // Runs on the Netty thread. Any unexpected exception here (e.g. racing the main thread's
        // entity-list mutation during an explosion) must never escape and kill the connection.
        try {
            if (s12PacketClass.isInstance(msg)) {
                // PROBE: confirm S12 knockback packets actually reach this handler (interception alive).
                if (++s12Diag % 5 == 0) {
                    CoreLogger.info("[ForgePacketInterceptor] S12 packet reached handler (interception alive)")
                }
                val mc = try { MappingContext.invokeMethod(null, "forge:mc_getMinecraft") } catch (_: Exception) { null }
                val player = try { MappingContext.getFieldValue(mc, "forge:mc_thePlayer") } catch (_: Exception) { null }
                val packetEntityId = try {
                    MappingContext.getFieldValue(msg, "forge:S12PacketEntityVelocity_entityID") as? Int
                } catch (_: Exception) { null }
                if (packetEntityId != null && player != null) {
                    val playerEntityId = try {
                        MappingContext.getFieldValue(player, "forge:entity_entityId") as? Int
                    } catch (_: Exception) { null }
                    if (packetEntityId == playerEntityId) {
                        // Knockback Displace: rotate the packet's motion around Y BEFORE it lands,
                        // so the direction change applies regardless of whether Velocity modifies
                        // the motion (vanilla Pass included). Distance unchanged (vanilla flaw).
                        applyDisplaceToPacket(msg)

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
                    } else {
                        // S12 for ANOTHER entity — feed the dealt-KB observer (KnockbackDisplay)
                        // so it can correlate this knockback with the player's own attack.
                        val motion = readS12Motion(msg)
                        if (motion != null) {
                            EventBridge.notifyEntityVelocity(packetEntityId, motion)
                        }
                        // PROBE: packet reached but entity-id match failed — the likely failure point.
                        if (++s12MismatchDiag % 5 == 0) {
                            CoreLogger.info(
                                "[ForgePacketInterceptor] S12 entityId=$packetEntityId playerId=$playerEntityId (mismatch or null)"
                            )
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
        } catch (_: Exception) {
            CoreLogger.debug("[ForgePacketInterceptor] packet handling swallowed an exception")
        }

        super.channelRead(ctx, msg)
    }

    /**
     * Read the S12 velocity motion vector (Int fields, 1/8000 blocks per tick units — the same
     * conversion ForgeEventBridge.onVelocityPacket applies for the player's own packets).
     */
    private fun readS12Motion(msg: Any): Vec3? {
        return try {
            val x = MappingContext.getFieldValue(msg, "forge:velocity_motionX") as? Int ?: return null
            val y = MappingContext.getFieldValue(msg, "forge:velocity_motionY") as? Int ?: return null
            val z = MappingContext.getFieldValue(msg, "forge:velocity_motionZ") as? Int ?: return null
            Vec3(x / 8000.0, y / 8000.0, z / 8000.0)
        } catch (_: Exception) { null }
    }

    /**
     * Knockback Displace: rotate the S12 packet's motion vector around Y by the active angle
     * (EventBridge.knockbackDisplaceAngle), writing the rotated Int fields (1/8000) back into the
     * packet. Runs on the Netty thread; any failure is swallowed (vanilla behaviour preserved).
     */
    private fun applyDisplaceToPacket(msg: Any) {
        val angle = EventBridge.knockbackDisplaceAngle
        if (angle == 0f) return
        try {
            val xRaw = MappingContext.getFieldValue(msg, "forge:velocity_motionX") as? Int ?: return
            val yRaw = MappingContext.getFieldValue(msg, "forge:velocity_motionY") as? Int ?: return
            val zRaw = MappingContext.getFieldValue(msg, "forge:velocity_motionZ") as? Int ?: return
            val rad = Math.toRadians(angle.toDouble())
            val cos = kotlin.math.cos(rad)
            val sin = kotlin.math.sin(rad)
            val nx = xRaw * cos - zRaw * sin
            val nz = xRaw * sin + zRaw * cos
            MappingContext.getField("forge:velocity_motionX")?.setInt(msg, kotlin.math.round(nx).toInt())
            MappingContext.getField("forge:velocity_motionZ")?.setInt(msg, kotlin.math.round(nz).toInt())
            MappingContext.getField("forge:velocity_motionY")?.setInt(msg, yRaw)
        } catch (_: Exception) {}
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
        if (netHandler.javaClass.name == "net.minecraft.network.NetworkManager") {
            return netHandler
        }
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
