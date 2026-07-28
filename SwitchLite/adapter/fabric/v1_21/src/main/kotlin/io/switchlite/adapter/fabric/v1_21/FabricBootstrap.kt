package io.switchlite.adapter.fabric.v1_21

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import io.switchlite.adapter.common.api.EventBridge
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWKeyCallbackI

/**
 * Fabric 1.21 bootstrap entry point.
 * Registers Fabric events and bridges them to common/module layer.
 */
object FabricBootstrap : ClientModInitializer {

    private var initialized = false
    private var previousKeyCallback: GLFWKeyCallbackI? = null
    private var keyCallbackInitialized = false

    // ========== Mouse Delta Tracking (Self-adaptive AimAssist) ==========
    private var prevCursorX: Double = 0.0
    private var prevCursorY: Double = 0.0
    private var cursorInitialized = false

    /**
     * Initialize Fabric bootstrap.
     * Called during mod initialization via ClientModInitializer.
     */
    override fun onInitializeClient() {
        if (initialized) return
        initialized = true

        // Capture mouse delta at START (before game consumes it)
        ClientTickEvents.START_CLIENT_TICK.register { client ->
            captureMouseDelta(client)
            // PreTick listeners (HitSelect)
            val player = FabricStateExtractor.extractPlayerState()
            val targetId = FabricStateExtractor.getCurrentTargetId()
            val target = if (targetId != null) FabricStateExtractor.extractTargetState(targetId) else null
            EventBridge.onStartTick(player, target)
        }

        // Register tick event via Fabric API
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            FabricEventBridge.onTick()
            setupKeyCallback(client)
        }

        // Initialize EventBridge platform handlers
        FabricEventBridge.registerListeners()

        // Velocity packet interception is handled by ClientPlayNetworkHandlerMixin
        // (registered via switchlite.mixins.json)

        println("[FabricBootstrap] Initialized")
    }

    /**
     * Capture mouse delta from GLFW cursor position and write to EventBridge.
     * Called at START_CLIENT_TICK so the delta represents the full frame.
     */
    private fun captureMouseDelta(client: net.minecraft.client.MinecraftClient) {
        val window = client.window ?: return
        val cursorXBuf = DoubleArray(1)
        val cursorYBuf = DoubleArray(1)
        GLFW.glfwGetCursorPos(window.handle, cursorXBuf, cursorYBuf)

        if (cursorInitialized) {
            EventBridge.mouseDeltaX = (cursorXBuf[0] - prevCursorX).toFloat()
            EventBridge.mouseDeltaY = (cursorYBuf[0] - prevCursorY).toFloat()
        }
        prevCursorX = cursorXBuf[0]
        prevCursorY = cursorYBuf[0]
        cursorInitialized = true

        EventBridge.mouseSensitivity = client.options.mouseSensitivity.toFloat()
        // Food level (Sprint: vanilla cancels at <= 6)
        EventBridge.foodLevel = client.player?.hungerManager?.foodLevel ?: 20
        // WASD key states (StrafeFix)
        EventBridge.isKeyBackDown = client.options.backKey.isPressed
        EventBridge.isKeyLeftDown = client.options.leftKey.isPressed
        EventBridge.isKeyRightDown = client.options.rightKey.isPressed
        // Physical mouse button states (ClickAssist, WTap 1.9+)
        EventBridge.isLeftMousePhysicallyDown =
            GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
        EventBridge.isRightMousePhysicallyDown =
            GLFW.glfwGetMouseButton(window.handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS
        // Fluid state (JumpReset: prevents jump in water/lava)
        EventBridge.isInFluid = client.player?.let { p ->
            p.isTouchingWater || p.isInLava
        } ?: false
    }

    /**
     * Set up GLFW key callback to capture all key events.
     * Chains with Minecraft's previous callback to preserve existing key handling.
     */
    private fun setupKeyCallback(client: net.minecraft.client.MinecraftClient) {
        if (keyCallbackInitialized) return
        val window = client.window ?: return

        previousKeyCallback = GLFW.glfwSetKeyCallback(window.handle, GLFWKeyCallbackI { windowHandle, key, scancode, action, mods ->
            // Call previous callback (Minecraft's) to preserve existing key handling
            previousKeyCallback?.invoke(windowHandle, key, scancode, action, mods)

            // Dispatch to EventBridge for module consumption
            val pressed = action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT
            io.switchlite.adapter.common.api.EventBridge.onKey(key, pressed)
        })

        keyCallbackInitialized = true
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
     * Sends PlayerInteractEntityC2SPacket (ATTACK) via the player's network handler.
     */
    private fun sendClickBurst(targetId: Int, times: Int) {
        val mc = net.minecraft.client.MinecraftClient.getInstance()
        val player = mc.player ?: return
        val world = mc.world ?: return
        val target = world.getEntityById(targetId) ?: return

        repeat(times) {
            val packet = net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket(
                target,
                player.isSneaking,
                net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket.ATTACK
            )
            player.networkHandler.sendPacket(packet)
        }
    }
}
