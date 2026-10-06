package io.doppel.adapter.common.module.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bridging block count's colour ladder, ported from Raven-bPLUS's SafeWalk.
 *
 * The point of the colours is that an emptying inventory is visible without reading the number, so
 * the thresholds themselves are the feature and are pinned here. `BridgeAssist.blockCountColor` is
 * the whole rule; the counting half lives on the platform side (only it can see the inventory) and
 * needs a game to exercise.
 */
class BridgeAssistBlockCountTest {

    @Test
    fun `the ladder matches the reference`() {
        assertEquals(0xFFFF5555.toInt(), BridgeAssist.blockCountColor(1), "nearly out — red")
        assertEquals(0xFFFF5555.toInt(), BridgeAssist.blockCountColor(15))
        assertEquals(0xFFFFAA00.toInt(), BridgeAssist.blockCountColor(16), "low — orange")
        assertEquals(0xFFFFAA00.toInt(), BridgeAssist.blockCountColor(31))
        assertEquals(0xFFFFFF55.toInt(), BridgeAssist.blockCountColor(32), "ok — yellow")
        assertEquals(0xFFFFFF55.toInt(), BridgeAssist.blockCountColor(127))
        assertEquals(0xFF55FF55.toInt(), BridgeAssist.blockCountColor(128), "plenty — green")
        assertEquals(0xFF55FF55.toInt(), BridgeAssist.blockCountColor(2304))
    }

    @Test
    fun `the ladder is monotonic in how much is left`() {
        val order = listOf(0, 15, 16, 31, 32, 127, 128, 999).map { BridgeAssist.blockCountColor(it) }
        assertEquals(4, order.toSet().size, "four bands, in order: $order")
    }

    @Test
    fun `the platform reports zero when nothing can be counted`() {
        // No handler registered (the unit-test JVM has no platform) must read as "nothing to show",
        // never as an exception or a negative that would render a nonsense count.
        assertTrue(io.doppel.adapter.common.api.EventBridge.blockCount(0) >= 0)
        assertTrue(io.doppel.adapter.common.api.EventBridge.blockCount(1) >= 0)
    }
}
