package io.switchlite.adapter.common.api

/**
 * Platform-agnostic event bridge interface.
 * Implementations in Forge/Fabric translate platform-specific events to these common events.
 */
interface EventBridge {
    fun registerListeners()
    fun unregisterListeners()
}

data class AttackEvent(val targetId: Int)
data class PacketEvent(val packet: Any, var isCancelled: Boolean = false)
data class TickEvent()
