package io.switchlite.adapter.forge.v1_8_9

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent
import io.switchlite.core.logging.CoreLogger

/**
 * Forge 1.8.9 bootstrap entry point.
 * Registers Forge events, injects packet interceptor, and bridges to common/module layer.
 */
object ForgeBootstrap {

    private var initialized = false

    /**
     * Initialize Forge bootstrap.
     * Called during mod initialization (FMLInitializationEvent or mod constructor).
     */
    fun init() {
        if (initialized) return
        initialized = true

        // Register Forge event bus handlers (tick, key input)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)

        // Initialize EventBridge platform handlers (rotation setter, motion applier)
        ForgeEventBridge.registerListeners()

        // Inject Netty packet interceptor for velocity packet interception.
        // Must be called after player joins a world (netHandler exists).
        // We attempt here; if netHandler is null, ForgePacketInterceptor.inject()
        // will be retried on the first ClientTickEvent where netHandler is available.
        ForgePacketInterceptor.inject()

        CoreLogger.info("[ForgeBootstrap] Initialized")
    }

    /**
     * Handle client tick events.
     * Extracts player state and dispatches to EventBridge.
     * Also retries packet interceptor injection if it failed at init time.
     */
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        // Retry injection if netHandler wasn't available at init
        ForgePacketInterceptor.ensureInjected()

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
     * Handle disconnect — eject packet interceptor to avoid leaking into next session.
     */
    @SubscribeEvent
    fun onDisconnect(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        ForgePacketInterceptor.eject()
        CoreLogger.info("[ForgeBootstrap] Disconnected — packet interceptor ejected")
    }
}
