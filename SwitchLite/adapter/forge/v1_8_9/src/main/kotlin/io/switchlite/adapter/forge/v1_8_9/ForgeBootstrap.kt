package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyTranslator
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.render.ClickGUI
import io.switchlite.adapter.common.module.render.Fullbright
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.module.render.NoFOV
import io.switchlite.adapter.common.module.render.NoHurtCam
import io.switchlite.adapter.common.module.world.FastPlace
import io.switchlite.adapter.common.render.OverlayRenderer
import io.switchlite.adapter.common.render.RenderContext
import io.switchlite.core.logging.CoreLogger
import io.switchlite.agent.MappingContext

/**
 * Forge 1.8.9 bootstrap — pure reflection, zero MC/Forge compile dependencies.
 *
 * No @SubscribeEvent, no Forge event bus. Instead, Agent.java calls
 * tick()/onKey()/render() directly from its polling threads.
 *
 * Lifecycle:
 * 1. init() — register modules, wire EventBridge, inject packet interceptor
 * 2. tick() — called at 20Hz by Agent.java — extracts player state, dispatches to EventBridge
 * 3. onKey(lwjglCode, pressed) — called by Agent.java key poll thread
 * 4. render() — called by Javassist hook or Agent.java fallback — delegates to OverlayRenderer
 */
object ForgeBootstrap {

    private var initialized = false

    // Lazy class references
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val mouseGetDX by lazy { mouseClass.getMethod("getDX") }
    private val mouseGetDY by lazy { mouseClass.getMethod("getDY") }
    private val mouseIsButtonDown by lazy { mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType) }
    private val blocksClass by lazy { Class.forName("net.minecraft.init.Blocks") }
    private val blocksAir by lazy { try { blocksClass.getField("AIR").get(null) } catch (_: Exception) { null } }

    // Cached field refs for tick
    private val keybindingPressedField by lazy { MappingContext.getField("forge:keybinding_pressed") }

    // Version-specific render bridges (lazy, created once)
    private val glBridge by lazy { ForgeGL11Bridge() }

    fun init() {
        if (initialized) return
        initialized = true

        ForgeEventBridge.registerListeners()
        ForgePacketInterceptor.inject()

        ModuleRegistry.registerAll(
            AimAssist, AutoBlock, AutoClicker, BlockHit, ClickAssist,
            DelayRemover, HitSelect, JumpReset, KeepSprint, Reach,
            SprintReset, STap, SuperKnockback, TriggerBot, Velocity, WTap,
            NoJumpDelay, NoKeyboardFix, NoMouseFix, Sprint, Strafe, StrafeFix,
            AntiBot, AutoTool, BridgeAssist, Eagle, ParallaxStrike, Teams,
            ClickGUI, Fullbright, HUD, NoFOV, NoHurtCam,
            FastPlace
        )
        ModuleRegistry.initSafetyIntegration()
        ModuleRegistry.enable("ClickGUI")
        ModuleRegistry.enable("HUD")

        CoreLogger.info("[ForgeBootstrap] Initialized (reflection mode) — ${ModuleRegistry.size()} modules registered")
    }

    /**
     * Called by Agent.java at 20Hz on a background thread.
     * Extracts player state, dispatches to module layer.
     */
    fun tick() {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft") ?: return
            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return

            // Mouse delta
            try {
                EventBridge.mouseDeltaX = (mouseGetDX.invoke(null) as Int).toFloat()
                EventBridge.mouseDeltaY = (mouseGetDY.invoke(null) as Int).toFloat()
            } catch (_: Exception) {}
            try {
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")
                EventBridge.mouseSensitivity = MappingContext.getFieldValue(gs, "forge:gs_mouseSensitivity") as? Float ?: 1.0f
            } catch (_: Exception) {}

            // Physical mouse buttons
            try {
                EventBridge.isLeftMousePhysicallyDown = mouseIsButtonDown.invoke(null, 0) as Boolean
                EventBridge.isRightMousePhysicallyDown = mouseIsButtonDown.invoke(null, 1) as Boolean
            } catch (_: Exception) {}

            // Crosshair / fluid / food
            try {
                val objMouseOver = MappingContext.getFieldValue(mc, "forge:mc_objectMouseOver")
                val typeOfHit = MappingContext.getFieldValue(objMouseOver, "forge:movingObjectPosition_typeOfHit")
                val motClass = Class.forName("net.minecraft.util.MovingObjectPosition\$MovingObjectType")
                val blockType = motClass.enumConstants.firstOrNull { it.toString() == "BLOCK" }
                EventBridge.isLookingAtBlock = typeOfHit === blockType
            } catch (_: Exception) {}

            try {
                val inWater = MappingContext.invokeMethod(player, "forge:entity_isInWater") as? Boolean ?: false
                val inLava = MappingContext.invokeMethod(player, "forge:entity_isInLava") as? Boolean ?: false
                val inWeb = MappingContext.invokeMethod(player, "forge:entity_isInWeb") as? Boolean ?: false
                EventBridge.isInFluid = inWater || inLava || inWeb
            } catch (_: Exception) {}

            try {
                val foodStats = MappingContext.getFieldValue(player, "forge:player_foodStats")
                if (foodStats != null) {
                    EventBridge.foodLevel = MappingContext.getFieldValue(foodStats, "forge:foodStats_foodLevel") as? Int ?: 20
                }
            } catch (_: Exception) {}

            // WASD key states
            try {
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
                val readPressed: (String) -> Boolean = { key ->
                    val kb = MappingContext.getFieldValue(gs, key)
                    if (kb == null) false else keybindingPressedField?.getBoolean(kb) ?: false
                }
                EventBridge.isKeyForwardDown = readPressed("forge:gs_keyBindForward")
                EventBridge.isKeyBackDown = readPressed("forge:gs_keyBindBack")
                EventBridge.isKeyLeftDown = readPressed("forge:gs_keyBindLeft")
                EventBridge.isKeyRightDown = readPressed("forge:gs_keyBindRight")
            } catch (_: Exception) {}

            // PreTick — START phase logic
            val playerState = ForgeStateExtractor.extractPlayerState()
            val targetId = ForgeStateExtractor.getCurrentTargetId()
            val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null
            EventBridge.onStartTick(playerState, target)

        } catch (_: Exception) {}

        // END phase — packet interceptor retry + ForgeEventBridge.onTick
        try {
            ForgePacketInterceptor.ensureInjected()
            ForgeEventBridge.onTick()
        } catch (_: Exception) {}
    }

    /**
     * Called by Agent.java key poll thread when a LWJGL2 key event fires.
     */
    fun onKey(lwjglCode: Int, pressed: Boolean) {
        if (lwjglCode != 0) {
            val glfwCode = KeyTranslator.fromLwjgl2(lwjglCode)
            EventBridge.onKey(glfwCode, pressed)
        }
    }

    /**
     * Called by Javassist hook (or Agent.java fallback) on MC's render thread.
     * Constructs a RenderContext and delegates to OverlayRenderer.
     *
     * This method does NOT contain any GL calls or rendering logic —
     * all drawing is in OverlayRenderer, which is shared across versions.
     */
    fun render() {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft") ?: return
            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return
            val fontRendererObj = MappingContext.getFieldValue(mc, "forge:mc_fontRendererObj") ?: return

            val displayWidth = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: 854
            val displayHeight = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: 480
            val guiScale = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")?.let {
                MappingContext.getFieldValue(it, "forge:gs_guiScale") as? Int ?: 0
            } ?: 0
            val scale = if (guiScale == 0) 1 else guiScale
            val scaledWidth = displayWidth / scale
            val scaledHeight = displayHeight / scale

            val ctx = RenderContext(
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                fontRenderer = ForgeFontRendererBridge(fontRendererObj),
                gl = glBridge
            )

            OverlayRenderer.render(ctx)

        } catch (_: Exception) {}
    }

    fun onDisconnect() {
        ForgePacketInterceptor.eject()
        CoreLogger.info("[ForgeBootstrap] Disconnected — packet interceptor ejected")
    }
}
