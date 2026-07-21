package io.switchlite.core.logging

/**
 * Lightweight Core Logger
 * Provides logging capabilities without Minecraft dependencies
 */
object CoreLogger {
    
    private var logEnabled = true
    
    fun enable() { logEnabled = true }
    fun disable() { logEnabled = false }
    
    fun info(message: String) {
        if (logEnabled) println("[SwitchLite/INFO] $message")
    }
    
    fun warn(message: String) {
        if (logEnabled) println("[SwitchLite/WARN] $message")
    }
    
    fun error(message: String) {
        if (logEnabled) println("[SwitchLite/ERROR] $message")
    }
    
    fun debug(message: String) {
        if (logEnabled && System.getenv("SWITCHLITE_DEBUG") == "true") {
            println("[SwitchLite/DEBUG] $message")
        }
    }
}
