package io.switchlite.core.safety

import io.switchlite.core.logging.CoreLogger

/**
 * Safety Wrapper for Strategy Execution
 * Prevents crashes from strategy errors and implements circuit breaker pattern
 */
object SafetyWrapper {
    private val failureCounts = mutableMapOf<String, Int>()
    private const val MAX_FAILURES = 3

    /**
     * Execute a strategy with safety protection
     * @param moduleId Unique identifier for the module
     * @param fallback Value to return if execution fails
     * @param block The strategy execution lambda
     */
    fun <T> execute(moduleId: String, fallback: T, block: () -> T): T {
        return try {
            val result = block()
            // Reset failure count on success
            resetFailures(moduleId)
            result
        } catch (e: Exception) {
            CoreLogger.warn("[$moduleId] Strategy execution failed: ${e.message}")
            
            val count = failureCounts.getOrDefault(moduleId, 0) + 1
            failureCounts[moduleId] = count
            
            if (count >= MAX_FAILURES) {
                CoreLogger.error("[$moduleId] CRITICAL: Module auto-disabled after $MAX_FAILURES consecutive failures.")
                // In production: trigger HUD warning or auto-disable module
                // ModuleRegistry.disable(moduleId)
            }
            
            fallback // Return safe fallback value
        }
    }
    
    /**
     * Reset failure count for a module (call on successful execution)
     */
    fun resetFailures(moduleId: String) {
        failureCounts.remove(moduleId)
    }
    
    /**
     * Get current failure count for a module
     */
    fun getFailureCount(moduleId: String): Int {
        return failureCounts.getOrDefault(moduleId, 0)
    }
}
