package io.doppel.core.safety

import io.doppel.core.logging.CoreLogger
import io.doppel.core.logging.ReplayLogger

/**
 * Safety Wrapper for Strategy Execution.
 *
 * Implements circuit breaker pattern: if a module's strategy throws
 * [MAX_FAILURES] consecutive exceptions, the module is auto-disabled
 * via [disableCallback] (wired by ModuleRegistry at bootstrap).
 *
 * Constitution compliance:
 * - §1 Safety: prevents runaway modules from crashing the client.
 * - §2 Debuggability: logs every failure with stack trace; records to ReplayLogger.
 *
 * Core layer constraint: does NOT reference ModuleRegistry directly.
 * The adapter layer sets [disableCallback] during initialization.
 */
object SafetyWrapper {

    private val failureCounts = mutableMapOf<String, Int>()
    private const val MAX_FAILURES = 3

    /**
     * Callback invoked when a module exceeds the failure threshold.
     * Set by ModuleRegistry.initSafetyIntegration() at bootstrap.
     * Signature: (moduleId: String) -> Unit
     */
    var disableCallback: ((String) -> Unit)? = null

    /**
     * Execute a strategy with safety protection.
     *
     * @param moduleId Unique identifier for the module (typically Module.name).
     * @param fallback Value to return if execution fails.
     * @param block The strategy execution lambda.
     * @return The strategy result, or [fallback] on exception.
     */
    fun <T> execute(moduleId: String, fallback: T, block: () -> T): T {
        return try {
            val result = block()
            resetFailures(moduleId)
            result
        } catch (e: Exception) {
            val count = failureCounts.getOrDefault(moduleId, 0) + 1
            failureCounts[moduleId] = count

            CoreLogger.warn("[$moduleId] Strategy failure #$count: ${e.message}")
            ReplayLogger.log("strategy_failure", mapOf(
                "module" to moduleId,
                "failureCount" to count,
                "exception" to (e::class.simpleName ?: "Exception"),
                "message" to (e.message ?: "unknown")
            ))

            if (count >= MAX_FAILURES) {
                CoreLogger.error("[$moduleId] CRITICAL: $MAX_FAILURES consecutive failures — auto-disabling")
                ReplayLogger.log("module_auto_disabled", mapOf(
                    "module" to moduleId,
                    "totalFailures" to count
                ))
                disableCallback?.invoke(moduleId)
            }

            fallback
        }
    }

    /**
     * Execute a strategy that returns Unit (no meaningful fallback needed).
     */
    fun executeVoid(moduleId: String, block: () -> Unit) {
        execute(moduleId, Unit, block)
    }

    /**
     * Reset failure count for a module (called on successful execution).
     */
    fun resetFailures(moduleId: String) {
        failureCounts.remove(moduleId)
    }

    /**
     * Get current failure count for a module.
     */
    fun getFailureCount(moduleId: String): Int {
        return failureCounts.getOrDefault(moduleId, 0)
    }

    /**
     * Check if a module is in a degraded state (has failures but not yet disabled).
     */
    fun isDegraded(moduleId: String): Boolean {
        return failureCounts.getOrDefault(moduleId, 0) in 1 until MAX_FAILURES
    }

    /**
     * Clear all failure state (e.g. on client restart or config reload).
     */
    fun resetAll() {
        failureCounts.clear()
    }
}
