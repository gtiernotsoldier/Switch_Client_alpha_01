package io.switchlite.adapter.common

import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.module.Module
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Bridge between Agent.java (Java 8, DLL injection entry) and the common module layer.
 *
 * Uses pure reflection + string class names — ZERO Kotlin object imports.
 * Each module's class is loaded individually via Class.forName(), so a single
 * module's clinit failure cannot cascade to others.
 */
object AgentBridge {

    /** Module entries: (simple class name, package suffix under io.switchlite.adapter.common.module) */
    private val moduleEntries = listOf(
        // Combat
        "AimAssist" to "combat",
        "AutoBlock" to "combat",
        "AutoClicker" to "combat",
        "BlockHit" to "combat",
        "ClickAssist" to "combat",
        "DelayRemover" to "combat",
        "HitSelect" to "combat",
        "JumpReset" to "combat",
        "KeepSprint" to "combat",
        "Reach" to "combat",
        "SprintReset" to "combat",
        "STap" to "combat",
        "SuperKnockback" to "combat",
        "TriggerBot" to "combat",
        "Velocity" to "combat",
        "WTap" to "combat",
        // Movement
        "NoJumpDelay" to "movement",
        "NoKeyboardFix" to "movement",
        "NoMouseFix" to "movement",
        "Sprint" to "movement",
        "Strafe" to "movement",
        "StrafeFix" to "movement",
        // Player
        "AntiBot" to "player",
        "AutoTool" to "player",
        "BridgeAssist" to "player",
        "Eagle" to "player",
        "ParallaxStrike" to "player",
        "Teams" to "player",
        // Render
        "ClickGUI" to "render",
        "Fullbright" to "render",
        "HUD" to "render",
        "NoFOV" to "render",
        "NoHurtCam" to "render",
        // World
        "FastPlace" to "world",
    )

    private val basePackage = "io.switchlite.adapter.common.module"

    private fun log(msg: String) {
        try { println("[AgentBridge] $msg") } catch (_: Exception) {}
    }

    private fun logErr(msg: String) {
        try { System.err.println("[AgentBridge] $msg") } catch (_: Exception) {}
    }

    private fun stackTrace(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /**
     * Register all modules via Class.forName + INSTANCE field reflection.
     * ZERO compile-time references to module classes — each one's clinit
     * is isolated in its own try-catch.
     */
    @JvmStatic
    fun initModules(): String {
        var ok = 0
        var failed = 0
        val failedNames = mutableListOf<String>()

        for ((name, pkg) in moduleEntries) {
            try {
                val className = "$basePackage.$pkg.$name"
                val clz = Class.forName(className)
                val field = clz.getDeclaredField("INSTANCE")
                val module = field.get(null) as Module
                ModuleRegistry.register(module)
                ok++
            } catch (e: Exception) {
                failed++
                failedNames.add(name)
                logErr("Module '$name' failed: ${e.javaClass.simpleName}")
                if (e.cause != null) logErr("  cause: ${e.cause.javaClass.simpleName}: ${e.cause.message}")
            } catch (e: NoClassDefFoundError) {
                failed++
                failedNames.add(name)
                logErr("Module '$name' missing dep: ${e.message}")
            } catch (e: ExceptionInInitializerError) {
                failed++
                failedNames.add(name)
                logErr("Module '$name' clinit failed: ${e.exception?.javaClass?.simpleName ?: "unknown"}")
            }
        }

        try { ModuleRegistry.initSafetyIntegration() } catch (_: Exception) {}
        try { if (ModuleRegistry.isRegistered("ClickGUI")) ModuleRegistry.enable("ClickGUI") } catch (_: Exception) {}
        try { if (ModuleRegistry.isRegistered("HUD")) ModuleRegistry.enable("HUD") } catch (_: Exception) {}

        val msg = "$ok registered, $failed failed" +
            if (failedNames.isNotEmpty()) " (${failedNames.joinToString()})" else ""
        log(msg)
        return msg
    }

    @JvmStatic
    fun getHudText(): String {
        return try {
            val names = ModuleRegistry.getEnabled()
                .filter { !it.hidden }
                .joinToString(" | ") { it.name }
            if (names.isNotEmpty()) "SwitchLite | $names" else "SwitchLite"
        } catch (e: Exception) { "SwitchLite" }
    }
}
