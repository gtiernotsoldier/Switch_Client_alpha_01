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
 * Each module is referenced individually inside its own try-catch because
 * accessing a Kotlin `object` triggers its `<clinit>` — a single failing
 * module must not prevent the rest from registering.
 */
object AgentBridge {

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun safeReg(module: Module, ok: IntArray, failed: MutableList<String>): Boolean {
        return try {
            ModuleRegistry.register(module)
            ok[0]++
            true
        } catch (e: Exception) {
            failed.add(module.name)
            CoreLogger.error("[AgentBridge] Module '${module.name}' failed: ${e.javaClass.simpleName}: ${e.message}")
            CoreLogger.error("[AgentBridge] ${stackTrace(e)}")
            false
        } catch (e: NoClassDefFoundError) {
            failed.add(module.name)
            CoreLogger.error("[AgentBridge] Module '${module.name}' missing dep: ${e.message}")
            false
        }
    }

    @JvmStatic
    fun initModules(): String {
        val ok = intArrayOf(0) // array for mutation inside inline fun
        val failed = mutableListOf<String>()

        // Each line is its own try-catch — accessing a Kotlin object triggers <clinit>
        try { safeReg(AimAssist, ok, failed) } catch (_: Throwable) { failed.add("AimAssist") }
        try { safeReg(AutoBlock, ok, failed) } catch (_: Throwable) { failed.add("AutoBlock") }
        try { safeReg(AutoClicker, ok, failed) } catch (_: Throwable) { failed.add("AutoClicker") }
        try { safeReg(BlockHit, ok, failed) } catch (_: Throwable) { failed.add("BlockHit") }
        try { safeReg(ClickAssist, ok, failed) } catch (_: Throwable) { failed.add("ClickAssist") }
        try { safeReg(DelayRemover, ok, failed) } catch (_: Throwable) { failed.add("DelayRemover") }
        try { safeReg(HitSelect, ok, failed) } catch (_: Throwable) { failed.add("HitSelect") }
        try { safeReg(JumpReset, ok, failed) } catch (_: Throwable) { failed.add("JumpReset") }
        try { safeReg(KeepSprint, ok, failed) } catch (_: Throwable) { failed.add("KeepSprint") }
        try { safeReg(Reach, ok, failed) } catch (_: Throwable) { failed.add("Reach") }
        try { safeReg(SprintReset, ok, failed) } catch (_: Throwable) { failed.add("SprintReset") }
        try { safeReg(STap, ok, failed) } catch (_: Throwable) { failed.add("STap") }
        try { safeReg(SuperKnockback, ok, failed) } catch (_: Throwable) { failed.add("SuperKnockback") }
        try { safeReg(TriggerBot, ok, failed) } catch (_: Throwable) { failed.add("TriggerBot") }
        try { safeReg(Velocity, ok, failed) } catch (_: Throwable) { failed.add("Velocity") }
        try { safeReg(WTap, ok, failed) } catch (_: Throwable) { failed.add("WTap") }
        try { safeReg(NoJumpDelay, ok, failed) } catch (_: Throwable) { failed.add("NoJumpDelay") }
        try { safeReg(NoKeyboardFix, ok, failed) } catch (_: Throwable) { failed.add("NoKeyboardFix") }
        try { safeReg(NoMouseFix, ok, failed) } catch (_: Throwable) { failed.add("NoMouseFix") }
        try { safeReg(Sprint, ok, failed) } catch (_: Throwable) { failed.add("Sprint") }
        try { safeReg(Strafe, ok, failed) } catch (_: Throwable) { failed.add("Strafe") }
        try { safeReg(StrafeFix, ok, failed) } catch (_: Throwable) { failed.add("StrafeFix") }
        try { safeReg(AntiBot, ok, failed) } catch (_: Throwable) { failed.add("AntiBot") }
        try { safeReg(AutoTool, ok, failed) } catch (_: Throwable) { failed.add("AutoTool") }
        try { safeReg(BridgeAssist, ok, failed) } catch (_: Throwable) { failed.add("BridgeAssist") }
        try { safeReg(Eagle, ok, failed) } catch (_: Throwable) { failed.add("Eagle") }
        try { safeReg(ParallaxStrike, ok, failed) } catch (_: Throwable) { failed.add("ParallaxStrike") }
        try { safeReg(Teams, ok, failed) } catch (_: Throwable) { failed.add("Teams") }
        try { safeReg(ClickGUI, ok, failed) } catch (_: Throwable) { failed.add("ClickGUI") }
        try { safeReg(Fullbright, ok, failed) } catch (_: Throwable) { failed.add("Fullbright") }
        try { safeReg(HUD, ok, failed) } catch (_: Throwable) { failed.add("HUD") }
        try { safeReg(NoFOV, ok, failed) } catch (_: Throwable) { failed.add("NoFOV") }
        try { safeReg(NoHurtCam, ok, failed) } catch (_: Throwable) { failed.add("NoHurtCam") }
        try { safeReg(FastPlace, ok, failed) } catch (_: Throwable) { failed.add("FastPlace") }

        ModuleRegistry.initSafetyIntegration()
        if (ModuleRegistry.isRegistered("ClickGUI")) ModuleRegistry.enable("ClickGUI")
        if (ModuleRegistry.isRegistered("HUD")) ModuleRegistry.enable("HUD")

        val msg = "[AgentBridge] ${ok[0]} registered, ${failed.size} failed" +
            if (failed.isNotEmpty()) " (${failed.joinToString()})" else ""
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
