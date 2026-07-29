package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyTranslator
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.render.ClickGUI
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.module.world.FastPlace
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
 * 4. render() — called by Agent.java via mc.addScheduledTask() on MC render thread
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

    // Cached field/method refs for render
    private val keybindingPressedField by lazy { MappingContext.getField("forge:keybinding_pressed") }

    // GL11 reflection cache
    private object ReflectGL11 {
        private val gl11 by lazy { Class.forName("org.lwjgl.opengl.GL11") }
        val GL_BLEND by lazy { gl11.getField("GL_BLEND").getInt(null) }
        val GL_DEPTH_TEST by lazy { gl11.getField("GL_DEPTH_TEST").getInt(null) }
        val GL_SRC_ALPHA by lazy { gl11.getField("GL_SRC_ALPHA").getInt(null) }
        val GL_ONE_MINUS_SRC_ALPHA by lazy { gl11.getField("GL_ONE_MINUS_SRC_ALPHA").getInt(null) }
        val GL_TEXTURE_2D by lazy { gl11.getField("GL_TEXTURE_2D").getInt(null) }
        val GL_QUADS by lazy { gl11.getField("GL_QUADS").getInt(null) }
        val glEnable by lazy { gl11.getMethod("glEnable", Int::class.javaPrimitiveType) }
        val glDisable by lazy { gl11.getMethod("glDisable", Int::class.javaPrimitiveType) }
        val glDepthMask by lazy { gl11.getMethod("glDepthMask", Boolean::class.java) }
        val glBlendFunc by lazy {
            gl11.getMethod("glBlendFunc", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        val glPushMatrix by lazy { gl11.getMethod("glPushMatrix") }
        val glColor4f by lazy {
            gl11.getMethod("glColor4f", Float::class.java, Float::class.java, Float::class.java, Float::class.java)
        }
        val glBegin by lazy { gl11.getMethod("glBegin", Int::class.javaPrimitiveType) }
        val glVertex2f by lazy {
            gl11.getMethod("glVertex2f", Float::class.java, Float::class.java)
        }
        val glEnd by lazy { gl11.getMethod("glEnd") }
        val glPopMatrix by lazy { gl11.getMethod("glPopMatrix") }
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
                val foodStats = MappingContext.getFieldValue(player, "forge:mc_thePlayer")
                    ?.let { /* FoodStats is a sub-object, access via player's field chain */ null }
                // foodStats is accessed via player.foodStats in MC — use direct field
                val fsField = player.javaClass.getDeclaredField("foodStats")
                fsField.isAccessible = true
                val fs = fsField.get(player)
                val flField = fs.javaClass.getDeclaredField("foodLevel")
                flField.isAccessible = true
                EventBridge.foodLevel = flField.getInt(fs)
            } catch (_: Exception) {}

            // WASD key states
            try {
                val gs = MappingContext.getFieldValue(mc, "forge:mc_gameSettings") ?: return
                val readPressed: (String) -> Boolean = { key ->
                    val kb = MappingContext.getFieldValue(gs, key) ?: return@readPressed false
                    keybindingPressedField?.getBoolean(kb) ?: false
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
     * Called by Agent.java via mc.addScheduledTask() on MC's main/render thread.
     * Draws HUD text, ClickGUI panel, and notifications.
     */
    fun render() {
        try {
            val mc = MappingContext.invokeMethod(null, "forge:mc_getMinecraft") ?: return
            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return
            val fontRenderer = MappingContext.getFieldValue(mc, "forge:mc_fontRendererObj") ?: return

            val displayWidth = MappingContext.getFieldValue(mc, "forge:mc_displayWidth") as? Int ?: 854
            val displayHeight = MappingContext.getFieldValue(mc, "forge:mc_displayHeight") as? Int ?: 480
            val guiScale = MappingContext.getFieldValue(mc, "forge:mc_gameSettings")?.let {
                MappingContext.getFieldValue(it, "forge:gs_guiScale") as? Int ?: 0
            } ?: 0
            val scale = if (guiScale == 0) 1 else guiScale
            val scaledWidth = displayWidth / scale
            val scaledHeight = displayHeight / scale

            val fontHeight = MappingContext.getFieldValue(fontRenderer, "forge:fontRenderer_FONT_HEIGHT") as? Int ?: 9
            val drawStringMethod = MappingContext.getMethod("forge:fontRenderer_drawStringWithShadow")
            val getStringWidthMethod = MappingContext.getMethod("forge:fontRenderer_getStringWidth")

            // Draw HUD
            val hudText = EventBridge.hudTextLine
            if (hudText.isNotEmpty()) {
                try {
                    drawStringMethod.invoke(fontRenderer, hudText, 4, 4, 0xFFFFFF)
                } catch (_: Exception) {}

                if (EventBridge.isGuiOpen) {
                    try {
                        drawStringMethod.invoke(fontRenderer, "\u00A7a[GUI Open] \u00A77RShift to close", 4, 4 + fontHeight + 2, 0x00FF00)
                    } catch (_: Exception) {}
                }
            }

            // Draw ClickGUI panel
            if (EventBridge.isGuiOpen) {
                drawClickGUI(fontRenderer, fontHeight, drawStringMethod, scaledHeight)
            }

            // Draw notifications
            val notifications = EventBridge.drainNotifications()
            if (notifications.isNotEmpty()) {
                var notifY = scaledHeight - notifications.size * (fontHeight + 4) - 4
                for (notif in notifications) {
                    val color = when (notif.type) {
                        EventBridge.NotificationType.SUCCESS -> 0x55FF55
                        EventBridge.NotificationType.ERROR -> 0xFF5555
                        EventBridge.NotificationType.INFO -> 0xFFFF55
                    }
                    val text = notif.text
                    val textWidth = try { getStringWidthMethod.invoke(fontRenderer, text) as? Int ?: text.length * 6 } catch (_: Exception) { text.length * 6 }
                    try {
                        drawStringMethod.invoke(fontRenderer, text, scaledWidth - textWidth - 6, notifY, color)
                    } catch (_: Exception) {}
                    notifY += fontHeight + 4
                }
            }
        } catch (_: Exception) {}
    }

    private fun drawClickGUI(fontRenderer: Any, fontHeight: Int, drawString: java.lang.invoke.MethodHandle, scaledHeight: Int) {
        val categories = io.switchlite.adapter.common.module.Category.values()
        val panelX = 40
        var panelY = 30
        val lineHeight = fontHeight + 3
        val padding = 6

        var totalHeight = padding * 2
        for (cat in categories) {
            totalHeight += lineHeight + 2
            val modules = ModuleRegistry.getByCategory(cat)
            totalHeight += modules.size * lineHeight
            totalHeight += 4
        }
        totalHeight += 20

        val panelWidth = 220
        drawRect(panelX - padding, panelY - padding, panelX + panelWidth, panelY + totalHeight, 0x80000000.toInt())

        for (cat in categories) {
            try {
                drawString.invoke(fontRenderer, "\u00A76${cat.name}", panelX, panelY, 0xFFFF55)
            } catch (_: Exception) {}
            panelY += lineHeight

            val modules = ModuleRegistry.getByCategory(cat)
            for (module in modules) {
                if (module.hidden) continue
                val stateColor = if (module.enabled) 0x55FF55 else 0xAAAAAA
                val stateText = if (module.enabled) "[ON] " else "[OFF]"
                try {
                    drawString.invoke(fontRenderer, "$stateText${module.name}", panelX + 8, panelY, stateColor)
                } catch (_: Exception) {}
                panelY += lineHeight
            }
            panelY += 4
        }

        panelY += 8
        try {
            drawString.invoke(fontRenderer, "\u00A77Click modules to toggle | ESC to close", panelX, panelY, 0xAAAAAA)
        } catch (_: Exception) {}
    }

    private fun drawRect(x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        try {
            val g = ReflectGL11
            g.glEnable.invoke(null, g.GL_BLEND)
            g.glDisable.invoke(null, g.GL_DEPTH_TEST)
            g.glDepthMask.invoke(null, false)
            g.glBlendFunc.invoke(null, g.GL_SRC_ALPHA, g.GL_ONE_MINUS_SRC_ALPHA)
            g.glPushMatrix.invoke(null)
            g.glColor4f.invoke(
                null,
                ((color shr 16) and 0xFF) / 255f,
                ((color shr 8) and 0xFF) / 255f,
                (color and 0xFF) / 255f,
                ((color shr 24) and 0xFF) / 255f
            )
            g.glDisable.invoke(null, g.GL_TEXTURE_2D)
            g.glBegin.invoke(null, g.GL_QUADS)
            g.glVertex2f.invoke(null, x1.toFloat(), y2.toFloat())
            g.glVertex2f.invoke(null, x2.toFloat(), y2.toFloat())
            g.glVertex2f.invoke(null, x2.toFloat(), y1.toFloat())
            g.glVertex2f.invoke(null, x1.toFloat(), y1.toFloat())
            g.glEnd.invoke(null)
            g.glEnable.invoke(null, g.GL_TEXTURE_2D)
            g.glPopMatrix.invoke(null)
            g.glDepthMask.invoke(null, true)
            g.glEnable.invoke(null, g.GL_DEPTH_TEST)
            g.glDisable.invoke(null, g.GL_BLEND)
        } catch (_: Exception) {}
    }

    fun onDisconnect() {
        ForgePacketInterceptor.eject()
        CoreLogger.info("[ForgeBootstrap] Disconnected — packet interceptor ejected")
    }
}
