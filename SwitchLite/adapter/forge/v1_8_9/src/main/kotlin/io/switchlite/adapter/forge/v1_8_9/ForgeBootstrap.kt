package io.switchlite.adapter.forge.v1_8_9

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent
import net.minecraft.network.play.server.S12PacketEntityVelocity

/**
 * Forge 1.8.9 bootstrap entry point.
 * Registers Forge events and bridges them to common/module layer.
 */
object ForgeBootstrap {

    private var initialized = false

    /**
     * Initialize Forge bootstrap.
     * Called during mod initialization.
     */
    fun init() {
        if (initialized) return
        initialized = true

        // Register event handlers
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)

        // Initialize EventBridge platform handlers
        ForgeEventBridge.registerListeners()

        println("[ForgeBootstrap] Initialized")
    }

    /**
     * Handle client tick events.
     * Extracts player state and dispatches to EventBridge.
     */
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        ForgeEventBridge.onTick()
    }

    /**
     * Handle key input events.
     * Dispatches key events to EventBridge for module consumption.
     */
    @SubscribeEvent
    fun onKeyInput(event: InputEvent.KeyInputEvent) {
        val lwjglCode = org.lwjgl.input.Keyboard.getEventKey()
        val pressed = org.lwjgl.input.Keyboard.getEventKeyState()
        if (lwjglCode != 0) {
            val glfwCode = io.switchlite.adapter.common.api.KeyTranslator.fromLwjgl2(lwjglCode)
            io.switchlite.adapter.common.api.EventBridge.onKey(glfwCode, pressed)
        }
    }

    /**
     * Handle incoming packets.
     * Intercepts S12PacketEntityVelocity for velocity manipulation.
     */
    @SubscribeEvent
    fun onPacket(event: FMLNetworkEvent.ClientCustomPacketEvent) {
        // TODO: intercept S12PacketEntityVelocity
        // This requires packet event registration in Forge 1.8.9
    }

    /**
     * Handle velocity packet directly.
     * Called when S12PacketEntityVelocity is detected.
     */
    fun onVelocityPacket(packet: S12PacketEntityVelocity) {
        val command = ForgeEventBridge.onVelocityPacket(packet)

        when (command) {
            is io.switchlite.core.model.PlatformCommand.ModifyMotion -> {
                // Motion will be applied by ForgeEventBridge.applyMotion()
            }
            is io.switchlite.core.model.PlatformCommand.CancelPacket -> {
                // Cancel the packet - requires packet event cancellation
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
        val mc = net.minecraft.client.Minecraft.getMinecraft()
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        repeat(times) {
            // TODO: send C02PacketUseEntity via MappingContext
            println("[ForgeBootstrap] Click burst #$it on entity $targetId")
        }
    }
}
