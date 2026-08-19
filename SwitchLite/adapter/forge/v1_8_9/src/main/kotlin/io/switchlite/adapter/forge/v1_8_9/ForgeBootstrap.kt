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

        // ClickGUI opens as a real MC GuiScreen: MC owns the mouse grab /
        // cursor / keyboard (open -> cursor shows + crosshair frozen; close ->
        // back to gameplay). We only draw the UI via OverlayRenderer.
        EventBridge.registerGuiOpenHandler { open ->
            try {
                val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
                if (mc == null) {
                    CoreLogger.error("[ForgeBootstrap] guiOpenHandler: mc is null")
                    return@registerGuiOpenHandler
                }
                if (open) {
                    try {
                        // GuiScreen is ABSTRACT — cannot `new GuiScreen()`. Use the
                        // Javassist-generated concrete subclass (in the game CL).
                        val gameCL = Thread.currentThread().contextClassLoader
                        val factoryClass = Class.forName(
                            "io.switchlite.agent.ForgeGuiScreenFactory", true,
                            gameCL ?: javaClass.classLoader
                        )
                        val screen = factoryClass.getMethod("createGuiScreen", ClassLoader::class.java)
                            .invoke(null, gameCL)
                        if (screen == null) {
                            CoreLogger.error("[ForgeBootstrap] ForgeGuiScreenFactory returned null — GUI cannot open")
                            return@registerGuiOpenHandler
                        }
                        val invoked = MappingContext.invokeMethod(mc, "forge:mc_displayGuiScreen", screen)
                        CoreLogger.info("[ForgeBootstrap] ClickGUI screen opened (invoked=$invoked)")
                    } catch (e: Exception) {
                        CoreLogger.error("[ForgeBootstrap] open GuiScreen FAILED: ${e.javaClass.simpleName}: ${e.message}")
                        return@registerGuiOpenHandler
                    }
                } else {
                    MappingContext.invokeMethod(mc, "forge:mc_displayGuiScreen", null)
                }
                EventBridge.isGuiOpen = open
            } catch (e: Exception) {
                CoreLogger.error("[ForgeBootstrap] guiOpenHandler FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        CoreLogger.info("[ForgeBootstrap] Initialized (reflection mode) — ${ModuleRegistry.size()} modules registered")
    }

    /**
     * Called by Agent.java at 20Hz on a background thread.
     * Extracts player state, dispatches to module layer.
     */
    private val mouseGetX by lazy {
        try { mouseClass.getMethod("getX") } catch (_: Exception) { null }
    }
    private val mouseGetY by lazy {
        try { mouseClass.getMethod("getY") } catch (_: Exception) { null }
    }

    // ── Keyboard state polling (does NOT consume MC's key event queue) ──
    private val keyboardClass by lazy { Class.forName("org.lwjgl.input.Keyboard") }
    private val keyboardIsKeyDown by lazy { keyboardClass.getMethod("isKeyDown", Int::class.javaPrimitiveType) }
    private var lastGuiKeyDown = false

    /**
     * Poll keyboard STATE (isKeyDown — never Keyboard.next()) for our own
     * keybinds: RIGHT_SHIFT toggles the ClickGUI, and each module's keybind
     * toggles the module. Edge-detected so a press fires once. Runs on the
     * render thread (from render()). This is the ONLY place we touch keys —
     * we must NOT drain the LWJGL event queue (that raced MC's KeyBinding
     * input and caused the "press many times" lag).
     */
    private fun pollGuiKeys() {
        try {
            val rshiftDown = (keyboardIsKeyDown.invoke(null, 54) as? Boolean) ?: false
            if (rshiftDown && !lastGuiKeyDown) {
                CoreLogger.info("[ForgeBootstrap] RShift edge detected — toggling ClickGUI")
                ClickGUI.toggleFromPoll()
            }
            lastGuiKeyDown = rshiftDown
            // Module keybinds: each module's keybind is a GLFW code; translate
            // to LWJGL2 and check state with edge detection.
            for (module in ModuleRegistry.getAll()) {
                if (module.keybind <= 0 || module.keybind == 344) continue // 344 = RShift (GUI)
                val lwjgl = KeyTranslator.toLwjgl2(module.keybind)
                if (lwjgl <= 0) continue
                val down = (keyboardIsKeyDown.invoke(null, lwjgl) as? Boolean) ?: false
                val prev = moduleKeyStates[module.name] ?: false
                moduleKeyStates[module.name] = down
                if (down && !prev) {
                    module.tryKeybindToggle(module.keybind)
                }
            }
        } catch (e: Exception) {
            if (pollGuiKeysDiagLogged < 3) {
                CoreLogger.error("[ForgeBootstrap] pollGuiKeys FAILED: ${e.javaClass.simpleName}: ${e.message}")
                pollGuiKeysDiagLogged++
            }
        }
    }

    private var pollGuiKeysDiagLogged = 0

    private val moduleKeyStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Read the LWJGL mouse into EventBridge (scaled GUI coordinates) and
     * forward clicks to ClickGUI. Runs on the MC render thread (from render()).
     *
     * NOTE: MC now owns the mouse grab/cursor via the real GuiScreen (see
     * registerGuiOpenHandler). This function only READS the mouse to feed
     * ClickGUI interactions (panel drag, module click) — it does not touch
     * grab state, so it cannot fight MC.
     */
    private fun applyGuiMouseInput(mc: Any) {
        try {
            val displayWidth = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: 854
            val displayHeight = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: 480
            val guiScaleSetting = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")?.let {
                MappingContext.getFieldValue(it, "forge:gs_guiScale") as? Int ?: 0
            } ?: 0
            val scale = computeGuiScale(displayWidth, displayHeight, guiScaleSetting)

            val rawX = (mouseGetX?.invoke(null) as? Int) ?: 0
            val rawY = (mouseGetY?.invoke(null) as? Int) ?: 0
            EventBridge.guiMouseX = rawX / scale
            EventBridge.guiMouseY = (displayHeight - rawY) / scale
            EventBridge.guiLeftMouseDown = (mouseIsButtonDown.invoke(null, 0) as? Boolean) ?: false

            // Vanilla 1.8 fontHeight = 9 -> panel line height = 12
            ClickGUI.handleMouseInput(
                EventBridge.guiMouseX,
                EventBridge.guiMouseY,
                EventBridge.guiLeftMouseDown,
                displayWidth / scale,
                displayHeight / scale,
                12
            )
        } catch (_: Exception) {}
    }

    /**
     * Called by Agent.java at 20Hz on a background thread.
     *
     * RESPONSIBILITY: game/module logic ONLY. All UI interaction (mouse grab,
     * GUI mouse input) lives in render() on the MC render thread — it must NOT
     * be touched from this background thread (LWJGL Mouse / Keyboard state is
     * owned by the render thread; mutating it from here races MC and caused
     * the cursor/crosshair glitches).
     *
     * Extracts player state, dispatches to the module layer.
     */
    fun tick() {
        if (EventBridge.isGuiOpen) {
            // ClickGUI open: pause all module logic. UI is driven from render().
            return
        }

        // START phase — extract player state, dispatch to modules
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
            if (mc != null) {
                val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer")

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

                if (player != null) {
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
                        val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")
                        if (gs != null) {
                            val readPressed: (String) -> Boolean = { key ->
                                val kb = MappingContext.getFieldValue(gs, key)
                                if (kb == null) false else keybindingPressedField?.getBoolean(kb) ?: false
                            }
                            EventBridge.isKeyForwardDown = readPressed("forge:gs_keyBindForward")
                            EventBridge.isKeyBackDown = readPressed("forge:gs_keyBindBack")
                            EventBridge.isKeyLeftDown = readPressed("forge:gs_keyBindLeft")
                            EventBridge.isKeyRightDown = readPressed("forge:gs_keyBindRight")
                        }
                    } catch (_: Exception) {}

                    // PreTick — START phase logic
                    val playerState = ForgeStateExtractor.extractPlayerState()
                    val targetId = ForgeStateExtractor.getCurrentTargetId()
                    val target = if (targetId != null) ForgeStateExtractor.extractTargetState(targetId) else null
                    EventBridge.onStartTick(playerState, target)
                }
            }
        } catch (_: Exception) {}

        // END phase — always executed, even if START phase failed
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

    /** Whether render() has logged its first diagnostic (avoid spamming every frame). */
    private var renderDiagLogged = false

    /**
     * Called by Javassist hook (or Agent.java fallback) on MC's render thread.
     * Constructs a RenderContext and delegates to OverlayRenderer.
     *
     * This method does NOT contain any GL calls or rendering logic —
     * all drawing is in OverlayRenderer, which is shared across versions.
     */
    fun render() {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
            if (mc == null) {
                if (!renderDiagLogged) { CoreLogger.error("[ForgeBootstrap.render] mc_getMinecraft returned null"); renderDiagLogged = true }
                return
            }

            // ── UI interaction (render thread — this is its home) ──
            // Mouse grab/cursor is owned by MC's GuiScreen now (see
            // registerGuiOpenHandler). We only READ the mouse to feed ClickGUI
            // interactions (panel drag, module click).
            // Keyboard: poll state (isKeyDown, edge-detected) — never the event
            // queue (that raced MC's KeyBinding and caused input lag).
            try { pollGuiKeys() } catch (_: Exception) {}
            if (EventBridge.isGuiOpen) {
                // Detect ESC-close: MC closes our GuiScreen itself via
                // displayGuiScreen(null); currentScreen becomes null. Reset our
                // state so the ClickGUI overlay stops rendering.
                val currentScreen = MappingContext.getFieldValue(mc, "forge:mc_currentScreen")
                if (currentScreen == null) {
                    ClickGUI.markClosed()
                } else {
                    try { applyGuiMouseInput(mc) } catch (_: Exception) {}
                }
            }
            // NOTE: mc_thePlayer is NOT required for HUD rendering — the overlay
            // only needs mc + fontRendererObj. On the main menu thePlayer is
            // legitimately null (normal MC state), so early-returning here is
            // what made the main menu show nothing. Removed the gate: the HUD
            // (module list / "SwitchLite") must render in menus too.
            val fontRendererObj = MappingContext.getFieldValue(mc, "forge:mc_fontRendererObj")
            if (fontRendererObj == null) {
                if (!renderDiagLogged) { CoreLogger.error("[ForgeBootstrap.render] mc_fontRendererObj returned null"); renderDiagLogged = true }
                return
            }

            val displayWidth = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: 854
            val displayHeight = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: 480
            val guiScaleSetting = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")?.let {
                MappingContext.getFieldValue(it, "forge:gs_guiScale") as? Int ?: 0
            } ?: 0
            // MC 1.8.9 ScaledResolution algorithm. guiScale==0 means AUTO —
            // previously treated as 1, which made the HUD text smaller than
            // the vanilla HUD (larger logical canvas than the real one).
            val scale = computeGuiScale(displayWidth, displayHeight, guiScaleSetting)
            val scaledWidth = displayWidth / scale
            val scaledHeight = displayHeight / scale

            val ctx = RenderContext(
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                fontRenderer = ForgeFontRendererBridge(fontRendererObj),
                gl = glBridge
            )

            OverlayRenderer.render(ctx)

            // First successful render — clear diagnostic flag
            if (renderDiagLogged) {
                CoreLogger.info("[ForgeBootstrap.render] Render pipeline recovered successfully")
                renderDiagLogged = false
            }

        } catch (e: Exception) {
            if (!renderDiagLogged) {
                CoreLogger.error("[ForgeBootstrap.render] FAILED: ${e.javaClass.simpleName}: ${e.message}")
                renderDiagLogged = true
            }
        }
    }

    /**
     * MC 1.8.9 ScaledResolution scaling algorithm (mirrors vanilla logic).
     * guiScale == 0 means auto: the factor grows while the scaled size stays
     * >= 320x240 at the next factor step. Integer division like vanilla.
     */
    private fun computeGuiScale(displayWidth: Int, displayHeight: Int, guiScaleSetting: Int): Int {
        var setting = if (guiScaleSetting == 0) 1000 else guiScaleSetting
        var factor = 1
        while (factor < setting
            && displayWidth / (factor + 1) >= 320
            && displayHeight / (factor + 1) >= 240
        ) {
            factor++
        }
        return factor.coerceAtLeast(1)
    }

    fun onDisconnect() {
        ForgePacketInterceptor.eject()
        CoreLogger.info("[ForgeBootstrap] Disconnected — packet interceptor ejected")
    }
}

