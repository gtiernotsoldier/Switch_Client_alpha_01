package io.doppel.adapter.common.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The packet vault arbiter: the ownership model that replaced the raw `packetLagActive`
 * flag. The resolved flag must behave exactly like the old one for a single claimant
 * (that is what the platform executor reads), and the arbitration must hold up with
 * several claimants on the same tick.
 */
class PacketVaultArbitrationTest {

    @BeforeEach
    fun setUp() { EventBridge.reset() }

    @AfterEach
    fun tearDown() { EventBridge.reset() }

    // ---- Single claimant reads like the old flag ----------------------------

    @Test
    fun `a claim raises the flag and its release drops it`() {
        assertFalse(EventBridge.packetLagActive)
        EventBridge.claimPacketHold("FakeLag")
        assertTrue(EventBridge.packetLagActive)
        assertEquals("FakeLag", EventBridge.packetHoldOwner)

        EventBridge.releasePacketHold("FakeLag")
        assertFalse(EventBridge.packetLagActive)
        assertEquals("", EventBridge.packetHoldOwner)
    }

    // ---- Several claimants: the flag is the union ---------------------------

    @Test
    fun `a hold continues while any claimant remains`() {
        EventBridge.claimPacketHold("FakeLag")
        EventBridge.claimPacketHold("Kamui", EventBridge.PACKET_PRIORITY_PHASE)
        assertTrue(EventBridge.packetLagActive)

        // FakeLag's window ends: Kamui's phase keeps the connection held.
        EventBridge.releasePacketHold("FakeLag")
        assertTrue(EventBridge.packetLagActive, "releasing one source never releases the other's hold")
        assertEquals("Kamui", EventBridge.packetHoldOwner)

        EventBridge.releasePacketHold("Kamui")
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `the owner label is the highest-priority claimant`() {
        EventBridge.claimPacketHold("FakeLag")
        assertEquals("FakeLag", EventBridge.packetHoldOwner)

        // A phase outranks a routine lag window while both are live.
        EventBridge.claimPacketHold("Kamui", EventBridge.PACKET_PRIORITY_PHASE)
        assertEquals("Kamui", EventBridge.packetHoldOwner)

        EventBridge.releasePacketHold("Kamui")
        assertEquals("FakeLag", EventBridge.packetHoldOwner)
    }

    // ---- Release safety -----------------------------------------------------

    @Test
    fun `releasing with no claim still clears a stale resolved flag`() {
        // Simulates a leftover direct write (or a claim lost to a reset) before an
        // enable/disable path runs: the release must land the flag at false regardless.
        EventBridge.packetLagActive = true
        EventBridge.releasePacketHold("LagRange")
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `releaseAllClaims drops the packet hold too - a dead module cannot stall the connection`() {
        EventBridge.claimPacketHold("Kamui", EventBridge.PACKET_PRIORITY_PHASE)
        assertTrue(EventBridge.packetLagActive)

        // Module.disable(source) calls this — the module cannot forget to release.
        EventBridge.releaseAllClaims("Kamui")
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `releaseAllPacketHolds clears everything at once`() {
        EventBridge.claimPacketHold("FakeLag")
        EventBridge.claimPacketHold("Kamui", EventBridge.PACKET_PRIORITY_PHASE)
        EventBridge.releaseAllPacketHolds()
        assertFalse(EventBridge.packetLagActive)
        assertEquals("", EventBridge.packetHoldOwner)
    }

    // ---- The one-sided flush ------------------------------------------------

    @Test
    fun `a flush request is consumed exactly once`() {
        EventBridge.requestPacketFlush("Kamui", "phase release")
        assertEquals("Kamui: phase release", EventBridge.consumePacketFlush())
        assertNull(EventBridge.consumePacketFlush(), "one request, one flush")
    }

    @Test
    fun `no flush request means no flush`() {
        assertNull(EventBridge.consumePacketFlush())
    }

    @Test
    fun `reset clears the whole vault state`() {
        EventBridge.claimPacketHold("FakeLag")
        EventBridge.requestPacketFlush("Kamui", "test")
        EventBridge.reset()
        assertFalse(EventBridge.packetLagActive)
        assertFalse(EventBridge.packetFlushPending)
        assertEquals("", EventBridge.packetHoldOwner)
    }
}
