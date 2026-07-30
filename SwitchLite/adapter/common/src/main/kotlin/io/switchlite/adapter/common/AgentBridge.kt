package io.switchlite.adapter.common

import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.combat.*
import io.switchlite.adapter.common.module.movement.*
import io.switchlite.adapter.common.module.player.*
import io.switchlite.adapter.common.module.render.*
import io.switchlite.adapter.common.module.world.*
import io.switchlite.core.logging.CoreLogger
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bridge between Agent.java (Java 8, DLL injection entry) and the common module layer.
 *
 * Registers modules one-by-one with individual try-catch — a single failing
 * module won't prevent the rest from loading.
 */
object AgentBridge {

    private val allModules: List<Module> by lazy {
        listOf(
            // Combat — keep alphabetical
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
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    @JvmStatic
    fun initModules(): String {
        var ok = 0
        var failed = 0
        val failedNames = mutableListOf<String>()

        for (module in allModules) {
            try {
                ModuleRegistry.register(module)
                ok++
            } catch (e: Exception) {
                failed++
                failedNames.add(module.name)
                CoreLogger.error("[AgentBridge] Module '${module.name}' failed: ${e.javaClass.simpleName}: ${e.message}")
                CoreLogger.error("[AgentBridge] ${stackTrace(e)}")
            } catch (e: NoClassDefFoundError) {
                failed++
                failedNames.add(module.name)
                CoreLogger.error("[AgentBridge] Module '${module.name}' missing dep: ${e.message}")
            }
        }

        ModuleRegistry.initSafetyIntegration()

        // Enable UI modules only if they registered successfully
        if (ModuleRegistry.isRegistered("ClickGUI")) ModuleRegistry.enable("ClickGUI")
        if (ModuleRegistry.isRegistered("HUD")) ModuleRegistry.enable("HUD")

        val msg = "[AgentBridge] $ok registered, $failed failed" +
            if (failedNames.isNotEmpty()) " (${failedNames.joinToString()})" else ""
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
