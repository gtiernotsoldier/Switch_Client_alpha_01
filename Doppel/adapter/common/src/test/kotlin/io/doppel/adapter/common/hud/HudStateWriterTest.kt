package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.render.HUD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the shared-memory contract from the HUD externalisation plan (§5.3).
 *
 * The Rust reader in `Doppel/hud` parses exactly this layout, so any change here is a protocol
 * change: bump [HudStateWriter.VERSION] (the HUD refuses to draw on a mismatch) and update both
 * sides together. The assertions read the published file the same way the other process does.
 */
class HudStateWriterTest {

    @Test
    fun `publishes the documented 3600 byte layout`() {
        val entries = listOf(
            HUD.HUDEntry(name = "AutoClicker", value = "12.3 cps", highlight = true, isRed = true),
            HUD.HUDEntry(name = "Velocity", value = "", highlight = false, isRed = false),
        )

        HudStateWriter.publish(entries)

        val bytes = HudStateWriter.defaultFile().readBytes()
        assertEquals(HudStateWriter.STATE_SIZE, bytes.size, "state block size")
        assertEquals(3600, bytes.size, "the plan fixes the block at 3600 bytes")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(HudStateWriter.MAGIC, buf.getInt(0), "magic 'DOPP'")
        assertEquals(HudStateWriter.VERSION, buf.getInt(4), "protocol version")
        assertEquals(entries.size, buf.getInt(8), "count")

        // Entry 0: id(4) enabled(1) flash(1) pad(2) name(24) value(24)
        assertEquals("AutoClicker", readFixed(buf, 16 + 8, 24), "entry 0 name")
        assertEquals("12.3 cps", readFixed(buf, 16 + 32, 24), "entry 0 value")
        assertEquals(1, bytes[16 + 4].toInt(), "entry 0 enabled")
        assertEquals(1, bytes[16 + 5].toInt(), "entry 0 flash")
        assertEquals(1, bytes[16 + 6].toInt(), "entry 0 highlight (pad[0])")

        // Entry 1: no value, no flash.
        val e1 = 16 + HudStateWriter.ENTRY_SIZE
        assertEquals("Velocity", readFixed(buf, e1 + 8, 24), "entry 1 name")
        assertEquals("", readFixed(buf, e1 + 32, 24), "entry 1 value")
        assertEquals(0, bytes[e1 + 5].toInt(), "entry 1 flash")
    }

    @Test
    fun `stable ids and truncated names stay parseable`() {
        HudStateWriter.publish(listOf(HUD.HUDEntry(name = "A".repeat(60), value = "v".repeat(60))))
        val bytes = HudStateWriter.defaultFile().readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buf.getInt(8))
        // Fixed fields must stay NUL terminated so the reader never runs past the field.
        val name = readFixed(buf, 16 + 8, 24)
        assertEquals(23, name.length)
        assertEquals("v".repeat(23), readFixed(buf, 16 + 32, 24))
    }

    private fun readFixed(buf: ByteBuffer, offset: Int, size: Int): String {
        val b = ByteArray(size)
        for (i in 0 until size) b[i] = buf.get(offset + i)
        val end = b.indexOf(0.toByte()).let { if (it < 0) size else it }
        return String(b, 0, end, Charsets.UTF_8)
    }
}
