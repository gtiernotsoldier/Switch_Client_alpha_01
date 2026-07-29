package io.switchlite.adapter.forge.v1_8_9

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent
import net.minecraftforge.client.event.RenderGameOverlayEvent
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.render.ClickGUI
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.world.FastPlace
import io.switchlite.core.logging.CoreLogger
import org.lwjgl.input.Mouse

/**
 * Forge 1.8.9 bootstrap entry point.
 * Registers Forge events, injects packet interceptor, and bridges to common/module layer.
 *
 * Lifecycle:
 * 1. init() — register Forge events, wire EventBridge handlers, register & enable modules
 * 2. onClientTick() — extract player state each tick, dispatch to module layer
 * 3. onKeyInput() — translate LWJGL2 keys → GLFW, dispatch to EventBridge → ClickGUI
 * 4. onRenderOverlay() — draw HUD text + notifications on screen
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

        // Register Forge event bus handlers (tick, key input, render overlay)
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)

        // Initialize EventBridge platform handlers (rotation setter, motion applier)
        ForgeEventBridge.registerListeners()

        // Inject Netty packet interceptor for velocity packet interception.
        // Must be called after player joins a world (netHandler exists).
        // We attempt here; if netHandler is null, ForgePacketInterceptor.inject()
        // will be retried on the first ClientTickEvent where netHandler is available.
        ForgePacketInterceptor.inject()

        // ========== Register all modules into ModuleRegistry ==========
        ModuleRegistry.registerAll(
            // Combat
            AimAssist, AutoBlock, AutoClicker, BlockHit, ClickAssist,
            DelayRemover, HitSelect, JumpReset, KeepSprint, Reach,
            SprintReset, STap, SuperKnockback, TriggerBot, Velocity, WTap,
            // Movement
            NoJumpDelay, NoKeyboardFix, NoMouseFix, Sprint, Strafe, StrafeFix,
            // Player
            AntiBot, AutoTool, BridgeAssist, Eagle, ParallaxStrike, Teams,
            // Render
            ClickGUI, Fullbright, HUD, NoFOV, NoHurtCam,
            // World
            FastPlace
        )
        ModuleRegistry.initSafetyIntegration()

        // ========== Enable core render modules by default ==========
        // ClickGUI — listens for Right Shift to open/close GUI
        ModuleRegistry.enable("ClickGUI")
        // HUD — builds enabled-module list every tick for on-screen display
        ModuleRegistry.enable("HUD")

        CoreLogger.info("[ForgeBootstrap] Initialized — ${ModuleRegistry.size()} modules registered")
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
                // Crosshair state (ClickAssist: block filter)
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
     *
     * Flow: Forge InputEvent → LWJGL2 code → KeyTranslator → GLFW code → EventBridge.onKey()
     * → ClickGUI.keyListener (toggles isGuiOpen) → other module keybinds
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
     * Handle render overlay events.
     * Draws the HUD (enabled modules list) and notifications on screen.
     *
     * RenderGameOverlayEvent.Pre gives us a chance to draw before vanilla elements.
     * We only draw on the TEXT element type to avoid duplicating on every sub-element.
     */
    @SubscribeEvent
    fun onRenderOverlay(event: RenderGameOverlayEvent) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return
        if (event.isCanceled) return

        val mc = net.minecraft.client.Minecraft.getMinecraft()
        // Don't render when no player (main menu, loading screens)
        if (mc.thePlayer == null) return

        val resolution = event.resolution
        val fontRenderer = mc.fontRendererObj

        // ========== Draw HUD (enabled modules list) ==========
        val hudText = EventBridge.hudTextLine
        if (hudText.isNotEmpty()) {
            // Top-left corner, below the debug overlay (F3)
            // Shadow text for readability
            fontRenderer.drawStringWithShadow(
                hudText,
                4,
                4,
                0xFFFFFF // white
            )

            // If ClickGUI is open, show "GUI OPEN" indicator
            if (EventBridge.isGuiOpen) {
                val guiY = 4 + fontRenderer.FONT_HEIGHT + 2
                fontRenderer.drawStringWithShadow(
                    "\u00A7a[GUI Open] \u00A77RShift to close",
                    4,
                    guiY,
                    0x00FF00 // green
                )
            }
        }

        // ========== Draw ClickGUI panel (if open) ==========
        if (EventBridge.isGuiOpen) {
            drawClickGUI(mc, resolution, fontRenderer)
        }

        // ========== Draw notifications (bottom-right toast) ==========
        val notifications = EventBridge.drainNotifications()
        if (notifications.isNotEmpty()) {
            var notifY = resolution.scaledHeight - notifications.size * (fontRenderer.FONT_HEIGHT + 4) - 4
            for (notif in notifications) {
                val color = when (notif.type) {
                    EventBridge.NotificationType.SUCCESS -> 0x55FF55  // green
                    EventBridge.NotificationType.ERROR ->   0xFF5555  // red
                    EventBridge.NotificationType.INFO ->    0xFFFF55  // gold
                }
                val text = notif.text
                val textWidth = fontRenderer.getStringWidth(text)
                // Right-aligned, with shadow
                fontRenderer.drawStringWithShadow(
                    text,
                    resolution.scaledWidth - textWidth - 6,
                    notifY,
                    color
                )
                notifY += fontRenderer.FONT_HEIGHT + 4
            }
        }
    }

    /**
     * Draw a simple ClickGUI panel listing all modules by category.
     * This is a minimal text-based GUI — a proper OpenGL-drawn GUI can replace this later.
     *
     * Layout: semi-transparent dark background panel on the left side,
     * modules grouped by category with [ON]/[OFF] state indicators.
     */
    private fun drawClickGUI(
        mc: net.minecraft.client.Minecraft,
        resolution: net.minecraft.client.gui.ScaledResolution,
        fontRenderer: net.minecraft.client.gui.FontRenderer
    ) {
        val categories = io.switchlite.adapter.common.module.Category.values()
        val panelX = 40
        var panelY = 30
        val lineHeight = fontRenderer.FONT_HEIGHT + 3
        val padding = 6

        // Calculate panel height
        var totalHeight = padding * 2 // top + bottom padding
        for (cat in categories) {
            totalHeight += lineHeight + 2 // category header
            val modules = ModuleRegistry.getByCategory(cat)
            totalHeight += modules.size * lineHeight
            totalHeight += 4 // spacing after category
        }
        totalHeight += 20 // extra bottom space

        // Draw semi-transparent background
        val panelWidth = 220
        drawRect(panelX - padding, panelY - padding, panelX + panelWidth, panelY + totalHeight, 0x80000000.toInt())

        for (cat in categories) {
            // Category header (gold)
            fontRenderer.drawStringWithShadow(
                "\u00A76${cat.name}",
                panelX,
                panelY,
                0xFFFF55
            )
            panelY += lineHeight

            // Module entries
            val modules = ModuleRegistry.getByCategory(cat)
            for (module in modules) {
                if (module.hidden) continue // skip hidden modules (like ClickGUI itself)
                val stateColor = if (module.enabled) 0x55FF55 else 0xAAAAAA
                val stateText = if (module.enabled) "[ON] " else "[OFF]"
                val moduleText = "$stateText${module.name}"
                fontRenderer.drawStringWithShadow(
                    moduleText,
                    panelX + 8,
                    panelY,
                    stateColor
                )
                panelY += lineHeight
            }
            panelY += 4 // spacing between categories
        }

        // Draw instructions at bottom of panel
        panelY += 8
        fontRenderer.drawStringWithShadow(
            "\u00A77Click modules to toggle | ESC to close",
            panelX,
            panelY,
            0xAAAAAA
        )
    }

    /**
     * Draw a filled rectangle (GL11 based, works in Forge 1.8.9).
     * This is the standard Forge 1.8.9 approach for GUI backgrounds.
     */
    private fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val mc = net.minecraft.client.Minecraft.getMinecraft()
        val scale = mc.gameSettings.guiScale
        val screenWidth = mc.displayWidth
        val screenHeight = mc.displayHeight

        // Enable blending for transparency
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND)
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
        org.lwjgl.opengl.GL11.glDepthMask(false)
        org.lwjgl.opengl.GL11.glBlendFunc(
            org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
            org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
        )

        // Save current matrix
        org.lwjgl.opengl.GL11.glPushMatrix()
        org.lwjgl.opengl.GL11.glColor4f(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f
        )

        // Disable texture to draw solid rect
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)

        org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_QUADS)
        org.lwjgl.opengl.GL11.glVertex2f(x1.toFloat(), y2.toFloat())
        org.lwjgl.opengl.GL11.glVertex2f(x2.toFloat(), y2.toFloat())
        org.lwjgl.opengl.GL11.glVertex2f(x2.toFloat(), y1.toFloat())
        org.lwjgl.opengl.GL11.glVertex2f(x1.toFloat(), y1.toFloat())
        org.lwjgl.opengl.GL11.glEnd()

        // Restore state
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D)
        org.lwjgl.opengl.GL11.glPopMatrix()
        org.lwjgl.opengl.GL11.glDepthMask(true)
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST)
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND)
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
