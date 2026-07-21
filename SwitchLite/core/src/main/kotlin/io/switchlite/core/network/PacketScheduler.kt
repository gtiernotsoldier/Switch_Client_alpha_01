package io.switchlite.core.network

/**
 * Global packet scheduler for network behavior simulation
 * Manages packet timing, delays, and realistic network artifacts
 */
class PacketScheduler {
    
    private val packetQueue = mutableListOf<ScheduledPacket>()
    private var tickCount = 0
    
    /**
     * Schedule a packet for delayed sending
     */
    fun schedule(packet: Any, delayTicks: Int = 0, delayMs: Int = 0) {
        val scheduledTime = tickCount + delayTicks
        packetQueue.add(ScheduledPacket(packet, scheduledTime, delayMs))
    }
    
    /**
     * Process queued packets (call each tick)
     */
    fun processQueue(): List<Any> {
        val toSend = mutableListOf<Any>()
        val currentTime = tickCount
        
        packetQueue.removeAll { scheduled ->
            if (scheduled.tickDelay <= 0 && scheduled.msDelay <= 0) {
                toSend.add(scheduled.packet)
                true
            } else if (scheduled.tickDelay > 0) {
                scheduled.tickDelay--
                false
            } else {
                false
            }
        }
        
        return toSend
    }
    
    /**
     * Advance tick counter
     */
    fun tick() {
        tickCount++
    }
    
    /**
     * Clear all queued packets
     */
    fun clear() {
        packetQueue.clear()
    }
    
    /**
     * Get queue size for debugging
     */
    fun getQueueSize(): Int = packetQueue.size
}

/**
 * Scheduled packet wrapper
 */
data class ScheduledPacket(
    val packet: Any,
    var tickDelay: Int,
    var msDelay: Int
)
