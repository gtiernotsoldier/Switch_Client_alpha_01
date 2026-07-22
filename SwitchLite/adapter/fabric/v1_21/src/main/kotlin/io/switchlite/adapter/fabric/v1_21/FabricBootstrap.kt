package io.switchlite.adapter.fabric.v1_21

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket

/**
 * Fabric 1.21 bootstrap entry point.
 * Registers Fabric events and bridges them to common/module layer.
 */
object FabricBootstrap : ClientModInitializer {

    private var initialized = false

    /**
     * Initialize Fabric bootstrap.
     * Called during mod initialization via ClientModInitializer.
     */
    override fun onInitializeClient() {
        if (initialized) return
        initialized = true

        // Register tick event via Fabric API
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            FabricEventBridge.onTick()
        }

        // Initialize EventBridge platform handlers
        FabricEventBridge.registerListeners()

        // TODO: Register packet interceptor mixin for EntityVelocityUpdateS2CPacket
        // This requires a Mixin into ClientPlayNetworkHandler to intercept velocity packets
        // before they are applied to the player.

        println("[FabricBootstrap] Initialized")
    }

    /**
     * Handle velocity packet from Mixin interceptor.
     * Called when EntityVelocityUpdateS2CPacket is detected.
     */
    fun onVelocityPacket(packet: EntityVelocityUpdateS2CPacket) {
        val command = FabricEventBridge.onVelocityPacket(packet)

        when (command) {
            is io.switchlite.core.model.PlatformCommand.ModifyMotion -> {
                // Motion will be applied by FabricEventBridge.applyMotion()
            }
            is io.switchlite.core.model.PlatformCommand.CancelPacket -> {
                // Cancel the packet - handled by Mixin cancelling the injection
            }
            is io.switchlite.core.model.PlatformCommand.ClickBurst -> {
                // Send click packets
                sendClickBurst(command.targetId, command.times)
            }
            is io.switchlite.core.model.PlatformCommand.Pass -> {
                // Do nothing, let original motion pass through
            }
            else -> {}
        }
    }

    /**
     * Send click burst to target.
     */
    private fun sendClickBurst(targetId: Int, times: Int) {
        val mc = net.minecraft.client.MinecraftClient.getInstance()
        val player = mc.player ?: return
        val world = mc.world ?: return

        repeat(times) {
            // TODO: send PlayerInteractEntityC2SPacket via MappingContext
            println("[FabricBootstrap] Click burst #$it on entity $targetId")
        }
    }
}
