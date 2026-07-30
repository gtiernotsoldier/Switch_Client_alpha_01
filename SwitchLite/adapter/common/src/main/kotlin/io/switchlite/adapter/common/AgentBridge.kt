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
 * Agent.java calls initModules() and getHudText() via reflection.
 * Lives in adapter:common — always available in the agent fat jar.
 */
object AgentBridge {

    @JvmStatic
    fun initModules(): String {
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
        ModuleRegistry.enable("ClickGUI")
        ModuleRegistry.enable("HUD")

        val msg = "[AgentBridge] ${ModuleRegistry.size()} modules registered, ClickGUI + HUD enabled"
        CoreLogger.info(msg)
        return msg
    }

    @JvmStatic
    fun getHudText(): String {
        val names = ModuleRegistry.getEnabled()
            .filter { !it.hidden }
            .joinToString(" | ") { it.name }
        return if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
    }
}
