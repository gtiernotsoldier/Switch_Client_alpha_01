package io.doppel.adapter.common.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The "what the server has seen" ring buffer: push/evict, the anchor fuse, and the
 * fossil readout. Pure tick-indexed data — no clock, no RNG.
 */
class TrackRecordTest {

    // TrackRecord is a shared singleton: a leftover trail from a previous test would make
    // "empty trail" assertions meaningless, so every test starts from a clean ring.
    @BeforeEach
    fun setUp() { TrackRecord.clear() }

    private fun frame(tick: Int, x: Double = tick.toDouble()) = TrackRecord.Frame(
        tick = tick,
        x = x, y = 64.0, z = 0.0,
        yaw = 0f, pitch = 0f,
        onGround = true
    )

    // ---- Ring behaviour -----------------------------------------------------

    @Test
    fun `lastSent is the newest frame`() {
        TrackRecord.push(frame(1))
        TrackRecord.push(frame(2))
        TrackRecord.push(frame(3))
        assertEquals(3, TrackRecord.lastSent()?.tick)
        assertEquals(3, TrackRecord.size())
    }

    @Test
    fun `oldest frames fall out past capacity`() {
        for (t in 0 until TrackRecord.DEFAULT_CAPACITY + 10) TrackRecord.push(frame(t))
        assertEquals(TrackRecord.DEFAULT_CAPACITY, TrackRecord.size())
        assertEquals(TrackRecord.DEFAULT_CAPACITY + 9, TrackRecord.lastSent()?.tick)
        // The first 10 frames are gone; the oldest survivor is tick 10.
        assertEquals(10, TrackRecord.lastSent()?.tick?.minus(TrackRecord.size())?.plus(1))
    }

    // ---- The anchor fuse ----------------------------------------------------

    @Test
    fun `mark pins the newest frame and anchor returns it`() {
        TrackRecord.push(frame(10))
        TrackRecord.push(frame(11))
        assertTrue(TrackRecord.mark())
        val anchor = TrackRecord.anchor()
        assertEquals(11, anchor?.tick)
        assertEquals(0, TrackRecord.anchorAge(11))
        assertEquals(2, TrackRecord.anchorAge(13), "age counts ticks since the pin")
    }

    @Test
    fun `marking an empty trail fails`() {
        assertEquals(false, TrackRecord.mark())
        assertNull(TrackRecord.anchor())
    }

    @Test
    fun `the anchor survives pushes after the pin`() {
        TrackRecord.push(frame(1))
        TrackRecord.mark()
        TrackRecord.push(frame(2))
        TrackRecord.push(frame(3))
        assertEquals(1, TrackRecord.anchor()?.tick)
        assertEquals(2, TrackRecord.anchorAge(3))
    }

    @Test
    fun `the anchor expires when the ring overwrites it`() {
        TrackRecord.push(frame(0))
        TrackRecord.mark()
        for (t in 1..TrackRecord.DEFAULT_CAPACITY) TrackRecord.push(frame(t))
        // The pinned frame (tick 0) fell out of the ring: the fuse has burned down.
        assertNull(TrackRecord.anchor(), "an anchor older than capacity is not an anchor")
        assertEquals(-1, TrackRecord.anchorAge(TrackRecord.DEFAULT_CAPACITY))
    }

    @Test
    fun `clear wipes trail and anchor`() {
        TrackRecord.push(frame(1))
        TrackRecord.mark()
        TrackRecord.clear()
        assertEquals(0, TrackRecord.size())
        assertNull(TrackRecord.anchor())
    }

    @Test
    fun `clearAnchor unpins without touching the trail`() {
        TrackRecord.push(frame(1))
        TrackRecord.mark()
        TrackRecord.clearAnchor()
        assertNull(TrackRecord.anchor())
        assertEquals(1, TrackRecord.size())
    }
}
