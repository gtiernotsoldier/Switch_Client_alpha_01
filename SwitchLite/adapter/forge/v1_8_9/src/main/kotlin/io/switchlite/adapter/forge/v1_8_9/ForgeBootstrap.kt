package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyTranslator
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.render.Fullbright
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.module.render.NoFOV
import io.switchlite.adapter.common.module.render.NoHurtCam
import io.switchlite.adapter.common.module.render.WebUI
import io.switchlite.adapter.common.module.world.FastPlace
import io.switchlite.adapter.common.render.OverlayRenderer
import io.switchlite.adapter.common.render.RenderContext
import io.switchlite.adapter.common.render.FontFactory
import io.switchlite.adapter.common.render.SmoothFontRenderer
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
 * 4. render() — called by Javassist hook or Agent.java fallback.
 *
 * NOTE: The in-game ClickGUI / HUD overlay was removed in favor of a WebUI
 * panel (cross-version, community-maintainable). render() is kept as a no-op
 * only so the RenderBridge injection hook (which reflects `render()`) stays
 * valid — it draws nothing.
 */
object ForgeBootstrap {

    private var initialized = false

    /** Diagnostic — log render-hook liveness once. */
    private var renderDiagLogged = false

    // Lazy class references
    private val mouseClass by lazy { Class.forName("org.lwjgl.input.Mouse") }
    private val mouseGetDX by lazy { mouseClass.getMethod("getDX") }
    private val mouseGetDY by lazy { mouseClass.getMethod("getDY") }
    private val mouseIsButtonDown by lazy { mouseClass.getMethod("isButtonDown", Int::class.javaPrimitiveType) }
    private val blocksClass by lazy { Class.forName("net.minecraft.init.Blocks") }
    private val blocksAir by lazy { try { blocksClass.getField("AIR").get(null) } catch (_: Exception) { null } }

    // Cached field refs for tick
    private val keybindingPressedField by lazy { MappingContext.getField("forge:keybinding_pressed") }

    // Version-specific render bridges (lazy, created once) — HUD overlay.
    private val glBridge by lazy { ForgeGL11Bridge() }
    private val mouseGetX by lazy { try { mouseClass.getMethod("getX") } catch (_: Exception) { null } }
    private val mouseGetY by lazy { try { mouseClass.getMethod("getY") } catch (_: Exception) { null } }

    // Smooth font for the HUD. Built lazily once and defensively: it uses
    // java.awt for the glyph atlas, which may not be available on MC's
    // LaunchClassLoader, so we catch Throwable (incl. NoClassDefFoundError /
    // OutOfMemoryError from the 4MB atlas) and fall back to the vanilla font.
    // HUD must always render regardless.
    private var smoothFont: SmoothFontRenderer? = null
    private var smoothFontFailed = false

    private fun resolveFont(fallback: io.switchlite.adapter.common.render.FontRendererBridge): io.switchlite.adapter.common.render.FontRendererBridge {
        if (smoothFontFailed) return fallback
        if (smoothFont == null) {
            try {
                smoothFont = SmoothFontRenderer(FontFactory.loadRegular(16f), glBridge)
                CoreLogger.info("[ForgeBootstrap] Smooth font initialized for HUD")
            } catch (t: Throwable) {
                smoothFontFailed = true
                smoothFont = null
                CoreLogger.error("[ForgeBootstrap] SmoothFont init failed (${t.javaClass.simpleName}: ${t.message}) — using vanilla font")
            }
        }
        val sf = smoothFont
        return sf ?: fallback
    }

    // ── Keyboard state polling for module keybinds (state, edge-detected) ──
    // Polls Keyboard.isKeyDown() (NOT Keyboard.next(), which races MC) on the
    // render thread so modules bound to a key toggle once per press. Throttled
    // to a fraction of frames to keep the reflection cost negligible.
    private val keyboardClass by lazy { Class.forName("org.lwjgl.input.Keyboard") }
    private val keyboardIsKeyDown by lazy { keyboardClass.getMethod("isKeyDown", Int::class.javaPrimitiveType) }

    private var keybindFrame = 0
    private val moduleKeyStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun pollModuleKeybinds() {
        try {
            for (module in ModuleRegistry.getAll()) {
                if (module.keybind <= 0) continue
                val lwjgl = KeyTranslator.toLwjgl2(module.keybind)
                if (lwjgl <= 0) continue
                val down = (keyboardIsKeyDown.invoke(null, lwjgl) as? Boolean) ?: false
                val prev = moduleKeyStates[module.name] ?: false
                moduleKeyStates[module.name] = down
                if (down && !prev) {
                    module.tryKeybindToggle(module.keybind)
                }
            }
        } catch (_: Exception) {}
    }

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
            Fullbright, HUD, NoFOV, NoHurtCam,
            WebUI,
            FastPlace
        )
        ModuleRegistry.initSafetyIntegration()
        try { ModuleRegistry.enable("HUD") } catch (_: Exception) {}
        try { ModuleRegistry.enable("WebUI") } catch (_: Exception) {}

        CoreLogger.info("[ForgeBootstrap] Initialized (reflection mode) — ${ModuleRegistry.size()} modules registered")
    }

    /**
     * Called by Agent.java at 20Hz on a background thread.
     *
     * Extracts player state, dispatches to the module layer.
     */
    fun tick() {
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

    /**
     * Called by Javassist hook (or Agent.java fallback) on MC's render thread.
     *
     * Renders ONLY the in-game HUD (module status card) + toasts via
     * OverlayRenderer. The ClickGUI configuration panels were removed in favor
     * of a cross-version WebUI panel, so there is no in-game config UI.
     */
    fun render() {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
            if (mc == null) return

            if (!renderDiagLogged) {
                renderDiagLogged = true
                CoreLogger.info("[ForgeBootstrap.render] hook alive. HUD.enabled=${io.switchlite.adapter.common.module.render.HUD.enabled}, entries=${io.switchlite.adapter.common.module.render.HUD.hudEntries.size}")
            }

            // Module keybinds — poll keyboard state on the render thread, throttled.
            if (++keybindFrame % 4 == 0) {
                try { pollModuleKeybinds() } catch (_: Exception) {}
            }

            // Feed LWJGL mouse into EventBridge so the HUD card can be dragged.
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
            } catch (_: Exception) {}

            val fontRendererObj = MappingContext.getFieldValue(mc, "forge:mc_fontRendererObj")
            if (fontRendererObj == null) return

            val displayWidth = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: 854
            val displayHeight = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: 480
            val guiScaleSetting = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")?.let {
                MappingContext.getFieldValue(it, "forge:gs_guiScale") as? Int ?: 0
            } ?: 0
            val scale = computeGuiScale(displayWidth, displayHeight, guiScaleSetting)
            val scaledWidth = displayWidth / scale
            val scaledHeight = displayHeight / scale

            val ctx = RenderContext(
                scaledWidth = scaledWidth,
                scaledHeight = scaledHeight,
                fontRenderer = resolveFont(ForgeFontRendererBridge(fontRendererObj)),
                gl = glBridge
            )

            OverlayRenderer.render(ctx)

        } catch (e: Exception) {
            // HUD rendering must never crash the game — swallow and continue.
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
