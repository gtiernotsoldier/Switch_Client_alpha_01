package io.switchlite.core.condition

/**
 * Trigger condition interface for module activation
 */
interface TriggerCondition {
    /**
     * Check if condition is met
     * @return true if the module should activate
     */
    fun isMet(): Boolean
    
    /**
     * Reset condition state (for delay counters, etc.)
     */
    fun reset()
}
