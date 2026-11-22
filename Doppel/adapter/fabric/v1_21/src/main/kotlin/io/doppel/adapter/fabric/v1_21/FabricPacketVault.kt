package io.doppel.adapter.fabric.v1_21

import io.doppel.adapter.common.api.EventBridge
import io.doppel.contract.logging.CoreLogger

/**
 * The vault's Fabric executor — the outbound half the adapter never had.
 *
 * Forge holds packets inside a Netty pipeline handler with their channel promises;
 * Fabric gets the same result at a higher level: [ClientCommonNetworkHandlerMixin]
 * cancels `sendPacket` at HEAD for anything [onOutbound] asks to hold, and the held
 * packet objects are re-sent through `ClientConnection.send` on release — which does
 * NOT re-enter the handler, so there is no recursion and no re-hold.
 *
 * Semantics are identical to the Forge executor on purpose (the vault contract):
 *
 *  * hold = [EventBridge.packetLagActive] (resolved by the claim arbiter) with a hard
 *    wall-clock cap, so a module that dies mid-hold cannot stall the connection;
 *  * attack packets are never delayed — the backlog goes out first, then the attack,
 *    and the module is told via [EventBridge.packetLagAttackReleased];
 *  * keepalive replies are answered even mid-phase, WITHOUT draining the backlog
 *    (reordering a ping reply ahead of held movement is protocol-fine; draining here
 *    would silently end a phase because the server said hello);
 *  * a pending [EventBridge.consumePacketFlush] (Kamui's phase release) lets the
 *    backlog out regardless of any claim.
 *
 * The S2C position-sync failsafe (Forge's S08 path) is handled on Fabric by the same
 * principle but a different entry point (a mixin on the S2C handler is a follow-up);
 * until then the vault's wall-clock cap and the engine's own MaxHold bound the window.
 */
object FabricPacketVault {

    private const val CAP_MS = 2500L

    private val held = ArrayDeque<Any>()
    @Volatile private var holdStartedMs = 0L

    private fun holdActive(): Boolean {
        if (!EventBridge.packetLagActive) return false
        if (holdStartedMs == 0L) return true
        return System.currentTimeMillis() - holdStartedMs <= CAP_MS
    }

    /**
     * Decide one outbound packet. Returns true = the mixin cancels the send (held);
     * false = pass through. Runs on the client thread (sendPacket's caller).
     */
    fun onOutbound(packet: Any): Boolean {
        try {
            // A phase release outranks everything: backlog out, claims no longer decide.
            val flushReason = EventBridge.consumePacketFlush()
            if (flushReason != null) {
                drain()
                CoreLogger.info("[FabricPacketVault] vault flush ($flushReason)")
            }

            // Pings are answered even mid-hold.
            if (isKeepAlive(packet)) return false

            // An attack is never delayed (Raven's prevent-delaying-attacks, same as Forge).
            if (isAttack(packet)) {
                drain()
                EventBridge.packetLagAttackReleased = true
                return false
            }

            if (holdActive()) {
                if (holdStartedMs == 0L) holdStartedMs = System.currentTimeMillis()
                held.addLast(packet)
                return true
            }
            drain()
            return false
        } catch (t: Throwable) {
            // Never kill the send path over an interception bug.
            CoreLogger.debug("[FabricPacketVault] outbound handling swallowed: ${t.message}")
            return false
        }
    }

    /** Re-send what was held, straight through the connection (bypasses the handler mixin). */
    fun drain() {
        holdStartedMs = 0L
        if (held.isEmpty()) return
        val n = held.size
        val player = net.minecraft.client.MinecraftClient.getInstance().player ?: return
        val connection = player.networkHandler?.connection ?: return
        while (held.isNotEmpty()) {
            val packet = held.pollFirst()
            try {
                connection.send(packet)
            } catch (t: Throwable) {
                CoreLogger.debug("[FabricPacketVault] drain dropped a packet: ${t.message}")
            }
        }
        CoreLogger.info("[FabricPacketVault] flushed $n held packet(s)")
    }

    /** End-of-tick sweep: honor a flush request even when no new packet is being sent. */
    fun tick() {
        if (EventBridge.consumePacketFlush() != null) drain()
    }

    private fun isAttack(packet: Any): Boolean =
        packet is net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket

    private fun isKeepAlive(packet: Any): Boolean =
        packet is net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket
}
