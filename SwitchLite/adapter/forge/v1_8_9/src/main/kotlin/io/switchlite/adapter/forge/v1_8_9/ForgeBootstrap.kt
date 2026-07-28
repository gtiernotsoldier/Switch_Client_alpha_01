package io.switchlite.adapter.forge.v1_8_9

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.logging.CoreLogger
import org.lwjgl.input.Mouse

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
        when (event.phase) {
            TickEvent.Phase.START -> {
                // Capture mouse delta before the game consumes it in EntityRenderer.
                // Mouse.getDX()/getDY() accumulate between Display.update() calls;
                // reading at START gives us the full frame delta.
                EventBridge.mouseDeltaX = Mouse.getDX().toFloat()
                EventBridge.mouseDeltaY = Mouse.getDY().toFloat()
                val mc = net.minecraft.client.Minecraft.getMinecraft()
                EventBridge.mouseSensitivity = mc.gameSettings.mouseSensitivity
                // Physical mouse button states (AutoBlock, ClickAssist)
                EventBridge.isLeftMousePhysicallyDown = Mouse.isButtonDown(0)
                EventBridge.isRightMousePhysicallyDown = Mouse.isButtonDown(1)
                // Crosshair state (ClickAssist: 仅方块 filter)
                EventBridge.isLookingAtBlock = mc.objectMouseOver != null &&
                    mc.objectMouseOver.typeOfHit == net.minecraft.util.MovingObjectPosition.MovingObjectType.BLOCK
                // Fluid state (JumpReset: prevents jump in water/lava/web)
                EventBridge.isInFluid = mc.thePlayer?.let { p ->
                    p.isInWater || p.isInLava || p.isInWeb
                } ?: false
                // Food level (Sprint: vanilla cancels at <= 6)
                EventBridge.foodLevel = mc.thePlayer?.foodStats?.foodLevel ?: 20
                // WASD key states (StrafeFix)
                EventBridge.isKeyForwardDown = mc.gameSettings.keyBindForward.pressed
                EventBridge.isKeyBackDown = mc.gameSettings.keyBindBack.pressed
                EventBridge.isKeyLeftDown = mc.gameSettings.keyBindLeft.pressed
                EventBridge.isKeyRightDown = mc.gameSettings.keyBindRight.pressed

                // PreTick listeners (HitSelect — runs before game processes input)
                val player = ForgeStateExtractor.extractPlayerState()
                val targetId = ForgeStateExtractor.getCurrentTargetId()
                val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null
                EventBridge.onStartTick(player, target)
            }
            TickEvent.Phase.END -> {
                // Retry injection if netHandler wasn't available at init
                ForgePacketInterceptor.ensureInjected()
                ForgeEventBridge.onTick()
            }
        }
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
