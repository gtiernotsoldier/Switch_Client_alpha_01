package io.doppel.adapter.forge.v1_8_9

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.api.KeyTranslator
import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.module.combat.*
import io.doppel.adapter.common.module.movement.*
import io.doppel.adapter.common.module.player.*
import io.doppel.adapter.common.module.render.Fullbright
import io.doppel.adapter.common.module.render.HUD
import io.doppel.adapter.common.module.render.Keystrokes
import io.doppel.adapter.common.module.render.NoFOV
import io.doppel.adapter.common.module.render.NoHurtCam
import io.doppel.adapter.common.module.render.Speedometer
import io.doppel.adapter.common.module.render.VelocityDisplay
import io.doppel.adapter.common.module.render.JumpStatus
import io.doppel.adapter.common.module.render.JumpTiming
import io.doppel.adapter.common.module.render.KnockbackDisplay
import io.doppel.adapter.common.module.render.WebUI
import io.doppel.adapter.common.module.world.FastPlace
import io.doppel.adapter.common.render.OverlayRenderer
import io.doppel.adapter.common.render.RenderContext
import io.doppel.adapter.common.render.FontFactory
import io.doppel.adapter.common.render.SmoothFontRenderer
import io.doppel.core.logging.CoreLogger
import io.doppel.agent.MappingContext

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

    /** Physical mouse button edge trackers — count real clicks for the CPS counter. */
    private var physLeftPrev = false
    private var physRightPrev = false

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

    // Smooth font for the HUD. DISABLED: its manual/reflected GL glyph-atlas
    // upload does not reliably render. The vanilla font renderer is what we use
    // for the HUD (known-good). Rolled back from attempting smooth font.
    private var smoothFont: SmoothFontRenderer? = null
    private var smoothFontFailed = true   // disabled → use vanilla font

    private fun resolveFont(fallback: io.doppel.adapter.common.render.FontRendererBridge): io.doppel.adapter.common.render.FontRendererBridge {
        val sf = smoothFont
        if (smoothFontFailed || sf == null) return fallback
        return sf
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
            ADTap, AimAssist, AutoBlock, AutoClicker, BlockHit, ClickAssist,
            DelayRemover, HitSelect, JumpReset, KeepSprint, KnockbackDelay, KnockbackDisplace, Reach,
            SprintReset, STap, SuperKnockback, TriggerBot, Velocity, WTap,
            NoJumpDelay, NoKeyboardFix, NoMouseFix, Sprint, Strafe, StrafeFix,
            AntiBot, AutoTool, BridgeAssist, Eagle, ParallaxStrike, Teams, TargetFilter,
            Fullbright, HUD, NoFOV, NoHurtCam, Keystrokes, Speedometer, VelocityDisplay, JumpStatus,
            JumpTiming, KnockbackDisplay,
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

                // NOTE: mouse delta is NOT sampled here — LWJGL Mouse.getDX/getDY are per-frame
                // buffers consumed on the render thread; sampling at 20Hz tick drops frames and
                // jitters with unstable tick intervals. It's read every render frame in render().

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
                            EventBridge.isKeyJumpDown = readPressed("forge:gs_keyBindJump")

                            // TRUE physical W/S via LWJGL Keyboard.isKeyDown(keyCode) — this is
                            // NOT affected by the tap modules' KeyBinding override. Matches
                            // Raven's Keyboard.isKeyDown(keyBindForward.getKeyCode()).
                            val fwdKb = MappingContext.getFieldValue(gs, "forge:gs_keyBindForward")
                            val backKb = MappingContext.getFieldValue(gs, "forge:gs_keyBindBack")
                            val fwdKey = fwdKb?.let { MappingContext.invokeMethod(it, "forge:keybinding_keyCode") as? Int } ?: 0
                            val backKey = backKb?.let { MappingContext.invokeMethod(it, "forge:keybinding_keyCode") as? Int } ?: 0
                            EventBridge.physicalForwardDown = fwdKey != 0 && ((keyboardIsKeyDown.invoke(null, fwdKey) as? Boolean) ?: false)
                            EventBridge.physicalBackDown = backKey != 0 && ((keyboardIsKeyDown.invoke(null, backKey) as? Boolean) ?: false)
                            // TRUE physical A/D via LWJGL — same mechanism as W/S above. ADTap
                            // reads these to never fight the player's own strafing fingers.
                            val leftKb = MappingContext.getFieldValue(gs, "forge:gs_keyBindLeft")
                            val rightKb = MappingContext.getFieldValue(gs, "forge:gs_keyBindRight")
                            val leftKey = leftKb?.let { MappingContext.invokeMethod(it, "forge:keybinding_keyCode") as? Int } ?: 0
                            val rightKey = rightKb?.let { MappingContext.invokeMethod(it, "forge:keybinding_keyCode") as? Int } ?: 0
                            EventBridge.physicalLeftDown = leftKey != 0 && ((keyboardIsKeyDown.invoke(null, leftKey) as? Boolean) ?: false)
                            EventBridge.physicalRightDown = rightKey != 0 && ((keyboardIsKeyDown.invoke(null, rightKey) as? Boolean) ?: false)
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
                CoreLogger.info("[ForgeBootstrap.render] hook alive. HUD.enabled=${io.doppel.adapter.common.module.render.HUD.enabled}, entries=${io.doppel.adapter.common.module.render.HUD.hudEntries.size}")
            }

            // Apply combat synthetic input (attack/use-item) on the MC main thread.
            // Modules write desired state on the background tick thread; this is the
            // only place that writes the real KeyBinding fields (race-free).
            try { ForgeEventBridge.applySyntheticInput() } catch (_: Exception) {}

            // Sample mouse delta EVERY render frame (LWJGL Mouse.getDX/getDY are per-frame buffers;
            // sampling them on the 20Hz tick drops frames and jitters with unstable tick intervals).
            try {
                EventBridge.mouseDeltaX = (mouseGetDX.invoke(null) as Int).toFloat()
                EventBridge.mouseDeltaY = (mouseGetDY.invoke(null) as Int).toFloat()
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")
                EventBridge.mouseSensitivity = MappingContext.getFieldValue(gs, "forge:gs_mouseSensitivity") as? Float ?: 1.0f
            } catch (_: Exception) {}

            // Apply the AimAssist desired rotation on the MAIN thread, per render FRAME (not per
            // 20Hz tick). The background tick only computes the target WORLD POINT; the rotation
            // is recomputed here every frame from the player's current eye position (smooth, no
            // 20Hz jumps). Player-yield also runs here per frame.
            try {
                val player = MappingContext.invokeMethod(null, "forge:mc_getMinecraft")
                    ?.let { MappingContext.getFieldValue(it, "forge:mc_thePlayer") }
                if (player != null) {
                    val curYaw = MappingContext.getFieldValue(player, "forge:player_rotationYaw") as? Float ?: 0f
                    val curPitch = MappingContext.getFieldValue(player, "forge:player_rotationPitch") as? Float ?: 0f
                    val posX = MappingContext.getFieldValue(player, "forge:entity_posX") as? Double ?: 0.0
                    val posY = MappingContext.getFieldValue(player, "forge:entity_posY") as? Double ?: 0.0
                    val posZ = MappingContext.getFieldValue(player, "forge:entity_posZ") as? Double ?: 0.0
                    val eye = io.doppel.core.util.Vec3(posX, posY + 1.62, posZ)
                    EventBridge.drainDesiredRotationFrame(
                        currentYaw = curYaw,
                        currentPitch = curPitch,
                        currentEye = eye,
                        mouseDeltaX = EventBridge.mouseDeltaX,
                        mouseDeltaY = EventBridge.mouseDeltaY
                    )
                }
            } catch (_: Exception) {}

            // Send queued SprintReset packets on the main thread (NetworkManager isn't
            // strictly thread-safe; this keeps addToSendQueue on the MC thread).
            try { EventBridge.drainPendingSprintReset() } catch (_: Exception) {}

            // Apply queued JumpReset jump pulses on the main thread (press/release the jump key so
            // MC's own tick performs the jump and the Keystrokes HUD reflects it).
            try { EventBridge.drainPendingJump() } catch (_: Exception) {}
            // Refresh Keystrokes key mirrors on the main thread — the 20Hz background sample could
            // miss the ~80ms JumpReset jump pulse, so read the real key state every render frame.
            try { ForgeEventBridge.refreshKeyDisplayState() } catch (_: Exception) {}

            // KeepSprint — pure module-layer, main thread. When the player is attacking + moving,
            // scale motionX/Z back up so the attack slowdown doesn't reduce speed (no inject needed).
            try { KeepSprint.onRenderFrame(mc) } catch (_: Exception) {}

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
                // Drag widgets only while a GUI screen is open (paused) — never in combat.
                EventBridge.isGuiOpen = MappingContext.getFieldValue(mc, "forge:mc_currentScreen") != null

                // Effective mouse button states for the Keystrokes HUD. When a full
                // clicker (AutoClicker/TriggerBot) overrides the attack/use key, the
                // state is driven by the cadence's press/release pulses (set in
                // ForgeEventBridge.pressKey/releaseKey) so the keys flash with the CPS
                // rhythm — do NOT overwrite with the physical hold (which is constant
                // true while the player holds left mouse, and would swallow the pulse).
                // When no clicker overrides, fall back to the physical mouse state.
                if (!EventBridge.syntheticAttackOverride) {
                    EventBridge.mouseButton0 = (mouseIsButtonDown.invoke(null, 0) as? Boolean) ?: false
                }
                if (!EventBridge.syntheticUseOverride) {
                    EventBridge.mouseButton1 = (mouseIsButtonDown.invoke(null, 1) as? Boolean) ?: false
                }

                // Physical click edge detection → feed the CPS counter (like Raven's
                // mouseManager, which counts every click, not just synthetic ones).
                val physLeft = (mouseIsButtonDown.invoke(null, 0) as? Boolean) ?: false
                val physRight = (mouseIsButtonDown.invoke(null, 1) as? Boolean) ?: false
                if (physLeft && !physLeftPrev) EventBridge.recordClick(0)
                if (physRight && !physRightPrev) EventBridge.recordClick(1)
                physLeftPrev = physLeft
                physRightPrev = physRight
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
