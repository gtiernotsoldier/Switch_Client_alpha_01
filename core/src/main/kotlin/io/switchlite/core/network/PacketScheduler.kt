package io.switchlite.core.network

/**
 * Global packet scheduler for simulating realistic network conditions.
 * This is a pure interface/abstraction in Core layer.
 * Concrete implementation must be in Adapter layer.
 */
interface PacketScheduler {
    /**
     * Schedule a packet for sending with optional delay and loss simulation.
     * @param packet The packet to send (opaque object, type defined by adapter)
     * @param delayMs Optional delay in milliseconds
     * @param lossChance Chance of packet loss (0-100)
     * @return true if packet was scheduled, false if dropped immediately
     */
    fun schedule(packet: Any, delayMs: Long = 0L, lossChance: Int = 0): Boolean
    
    /**
     * Flush all pending packets immediately.
     */
    fun flush()
    
    /**
     * Clear all pending packets without sending.
     */
    fun clear()
    
    /**
     * Get the number of pending packets.
     */
    val pendingCount: Int
}

/**
 * Default no-op implementation for testing.
 * Real implementation must be provided by Adapter layer.
 */
class NoOpPacketScheduler : PacketScheduler {
    override fun schedule(packet: Any, delayMs: Long, lossChance: Int): Boolean {
        return true // Always accept, never actually send
    }
    
    override fun flush() {}
    
    override fun clear() {}
    
    override val pendingCount get() = 0
}
