package io.doppel.adapter.fabric.v1_21

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import io.doppel.adapter.common.api.EventBridge
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWKeyCallbackI
import io.doppel.contract.model.PlatformCommand

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
            // Apply synthetic combat input (attack/use-item) on the main thread.
            FabricEventBridge.applySyntheticInput()
            // Apply queued JumpReset jump pulses on the main thread (press/release jump key).
            EventBridge.drainPendingJump()
            FabricEventBridge.onTick()
            // The vault's end-of-tick sweep: honor a flush request even when no new packet flows.
            FabricPacketVault.tick()

            // Izanagi's platform write: apply the replay step the core walked back
            // to. A step is a frame the server already accepted once — this is a
            // reposition, not a teleport; the corridor check already vetted the
            // segment before the core advanced.
            EventBridge.consumeReplayPosition()?.let { rp ->
                client.player?.refreshPositionAndAngles(rp.x, rp.y, rp.z, client.player!!.yaw, client.player!!.pitch)
            }
            setupKeyCallback(client)
        }

        // Initialize EventBridge platform handlers
        FabricEventBridge.registerListeners()

        // Packet interception on Fabric is the agent's ASM layer (the adapter carries no
        // SpongePowered Mixin). The agent's Transformer rewrites the HEAD of
        // class_8673.method_52787 sendPacket (all C2S traffic) and class_2743.method_11817
        // apply (S2C velocity) to call io.doppel.agent.PacketBridge — the two decision
        // objects below are what that bridge delegates to, so they must be registered.
        // Reflection because PacketBridge lives in the agent jar and this adapter must
        // stay compilable (and runnable) without it: the agent jar rides the system
        // classpath, so knot's parent chain sees it once the agent is attached. Without
        // the agent the hooks never fire anyway — one INFO line and pure pass-through.
        // Izanagi's platform surface: this adapter CAN reposition the player and
        // owns the corridor query, so it registers both — the capability gate and
        // the checker. A platform without either fails closed (no recalls), so
        // registering them here is what enables the module, not the module itself.
        EventBridge.supportsReplayPosition = true
        EventBridge.corridorChecker = { x, y, z -> corridorClear(client, x, y, z) }

        try {
            val bridgeClass = Class.forName("io.doppel.agent.PacketBridge")
            bridgeClass.getMethod("install", Any::class.java, Any::class.java)
                .invoke(null, FabricPacketVault, FabricVelocityInterceptor)
        } catch (_: ClassNotFoundException) {
            println("[FabricBootstrap] agent not attached — packet hooks inactive, adapter runs pass-through")
        } catch (t: Throwable) {
            println("[FabricBootstrap] PacketBridge install failed ($t) — packet hooks inactive, adapter runs pass-through")
        }

        // Register the module set (reflection fallback path — the same entries the agent
        // publishes). Idempotent: if a platform bootstrap registered them already, this
        // only wires safety integration and enables HUD/WebUI.
        io.doppel.adapter.common.AgentBridge.initModules()

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
        EventBridge.isKeyForwardDown = client.options.forwardKey.isPressed
        EventBridge.isKeyBackDown = client.options.backKey.isPressed
        EventBridge.isKeyLeftDown = client.options.leftKey.isPressed
        EventBridge.isKeyRightDown = client.options.rightKey.isPressed
        // Physical mouse button states (ClickAssist, BlockHit, HitSelect, KeepSprint)
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
            io.doppel.adapter.common.api.EventBridge.onKey(key, pressed)

            // Module keybind toggles (the Forge path polls keyboard state; Fabric is event
            // driven). PRESS only — REPEAT would toggle a module once per key-repeat tick.
            // Skipped while a screen is open, same rule as Forge's poll.
            if (action == GLFW.GLFW_PRESS && key != GLFW.GLFW_KEY_UNKNOWN && client.currentScreen == null) {
                try {
                    io.doppel.adapter.common.module.ModuleRegistry.getAll().forEach { module ->
                        module.tryKeybindToggle(key)
                    }
                } catch (_: Exception) {}
            }
        })

        keyCallbackInitialized = true
    }

    /**
     * The corridor judgement for Izanagi's replay: the segment is clear when the
     * player's own body volume fits at the target position (collision) AND the
     * feet block plus the one below it carry no danger source (lava, fire,
     * cactus). Deliberately conservative — anything the world query cannot
     * answer fails closed, because a false "clear" walks the body into geometry.
     */
    private fun corridorClear(client: net.minecraft.client.MinecraftClient, x: Double, y: Double, z: Double): Boolean {
        val world = client.world ?: return false
        val player = client.player ?: return false
        val box = player.boundingBox.offset(x - player.x, y - player.y, z - player.z)
        if (!world.isSpaceEmpty(player, box)) return false
        for (dy in intArrayOf(0, -1)) {
            val bp = net.minecraft.util.math.BlockPos.ofFloored(x, y + dy, z)
            val state = world.getBlockState(bp)
            if (state.isIn(net.minecraft.registry.tag.BlockTags.FIRE)) return false
            if (state.isOf(net.minecraft.block.Blocks.LAVA)) return false
            if (state.isOf(net.minecraft.block.Blocks.CACTUS)) return false
        }
        return true
    }

    /**
     * Handle velocity packet coming down the agent's ASM hook path
     * (class_2743.method_11817 apply HEAD → PacketBridge.onVelocityPacket →
     * FabricVelocityInterceptor.onVelocityPacket → here for the non-cancel outcomes).
     * Called when EntityVelocityUpdateS2CPacket is detected.
     */
    fun onVelocityPacket(packet: EntityVelocityUpdateS2CPacket) {
        val command = FabricEventBridge.onVelocityPacket(packet)

        when (command) {
            is io.doppel.contract.model.PlatformCommand.ModifyMotion -> {
                // Motion will be applied by FabricEventBridge.applyMotion()
            }
            is io.doppel.contract.model.PlatformCommand.CancelPacket -> {
                // Cancelled in the hook itself — FabricVelocityInterceptor answered true
                // and PacketBridge made apply() return before its body ran
            }
            is io.doppel.contract.model.PlatformCommand.ClickBurst -> {
                // Send click packets
                sendClickBurst(command.targetId, command.times)
            }
            is io.doppel.contract.model.PlatformCommand.Pass -> {
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
