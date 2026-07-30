package io.switchlite.adapter.common

import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.render.ClickGUI
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.module.render.Fullbright
import io.switchlite.adapter.common.module.render.NoFOV
import io.switchlite.adapter.common.module.render.NoHurtCam
import io.switchlite.adapter.common.module.world.FastPlace
import io.switchlite.core.logging.CoreLogger

/**
 * Bridge between Agent.java (Java 8, DLL injection entry) and the common module layer.
 *
 * Agent.java calls [initModules] via reflection after MappingContext is ready.
 * This registers all modules, enables the core UI modules (ClickGUI, HUD),
 * and wires SafetyWrapper auto-disable.
 *
 * This class lives in adapter:common (no Forge/Fabric dependency) so it's
 * always available in the agent fat jar, regardless of which platform adapter
 * is loaded.
 */
object AgentBridge {

    /**
     * Register all modules and enable core UI modules.
     * Called by Agent.java via reflection: AgentBridge.initModules()
     *
     * Returns a status string for Agent.java to log.
     */
    @JvmStatic
    fun initModules(): String {
        // Register all 35 modules
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

        // Enable core UI modules by default
        ModuleRegistry.enable("ClickGUI")
        ModuleRegistry.enable("HUD")

        val msg = "[AgentBridge] ${ModuleRegistry.size()} modules registered, ClickGUI + HUD enabled"
        CoreLogger.info(msg)
        return msg
    }

    /**
     * Check if ClickGUI is currently open.
     * Used by Agent.java to log GUI state for diagnostics.
     */
    @JvmStatic
    fun isGuiOpen(): Boolean = io.switchlite.adapter.common.api.EventBridge.isGuiOpen
}
