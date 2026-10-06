package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.render.HUD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the shared-memory contract v2 from the HUD externalisation plan (§5.3).
 *
 * The Rust reader in `Doppel/hud` parses exactly this layout, so any change here is a protocol
 * change: bump [HudStateWriter.VERSION] (the HUD refuses to draw on a mismatch) and update both
 * sides together. The assertions read the published file the same way the other process does.
 */
class HudStateWriterTest {

    /** Read the file the writer just published, checking the declared block size first. */
    private fun published(): ByteArray {
        val bytes = HudStateWriter.defaultFile().readBytes()
        assertEquals(HudStateWriter.STATE_SIZE, bytes.size, "state block size")
        assertEquals(4096, bytes.size, "contract v2 fixes the block at 4096 bytes")
        return bytes
    }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `publishes the documented v2 header and entries`() {
        val entries = listOf(
            HUD.HUDEntry(name = "AutoClicker", value = "12.3 cps", highlight = true, isRed = true),
            HUD.HUDEntry(name = "Velocity", value = "", highlight = false, isRed = false),
        )
        val header = HudStateWriter.Header(
            wave = true,
            spaced = true,
            watermark = false,
            mode = HudStateWriter.MODE_RISE,
            primary = 0x112233,
            secondary = 0xAABBCC,
            fps = 144,
            nickname = "Steve"
        )

        HudStateWriter.publish(entries, header)

        val bytes = published()
        val buf = le(bytes)
        assertEquals(HudStateWriter.MAGIC, buf.getInt(0), "magic 'DOPP'")
        assertEquals(2, buf.getInt(4), "the writer publishes v2 now")
        assertEquals(HudStateWriter.VERSION, buf.getInt(4), "protocol version constant")
        assertEquals(entries.size, buf.getInt(8), "count")

        val expectedFlags = HudStateWriter.FLAG_WAVE or HudStateWriter.FLAG_SPACED or
            (HudStateWriter.MODE_RISE shl HudStateWriter.MODE_SHIFT)
        assertEquals(expectedFlags, buf.getInt(12), "wave + spaced + mode rise; watermark off")
        assertEquals(0x112233, buf.getInt(16), "primary 0x00RRGGBB")
        assertEquals(0xAABBCC, buf.getInt(20), "secondary 0x00RRGGBB")
        assertEquals(144, buf.getInt(24), "fps")
        assertEquals(0, buf.getInt(28), "reserved")
        assertEquals("Steve", readFixed(buf, 32, 24), "nickname[24] at 32")
        assertEquals(0, buf.getInt(56), "reserved")
        assertEquals(0, buf.getInt(60), "reserved")

        // Entries start at 64 in v2; they started at 16 in v1.
        val e0 = HudStateWriter.HEADER_SIZE
        assertEquals("AutoClicker", readFixed(buf, e0 + 8, 24), "entry 0 name")
        assertEquals("12.3 cps", readFixed(buf, e0 + 32, 24), "entry 0 value")
        assertEquals(1, bytes[e0 + 4].toInt(), "entry 0 enabled")
        assertEquals(1, bytes[e0 + 5].toInt(), "entry 0 flash")
        assertEquals(1, bytes[e0 + 6].toInt(), "entry 0 highlight")

        val e1 = e0 + HudStateWriter.ENTRY_SIZE
        assertEquals("Velocity", readFixed(buf, e1 + 8, 24), "entry 1 name")
        assertEquals("", readFixed(buf, e1 + 32, 24), "entry 1 value")
        assertEquals(0, bytes[e1 + 5].toInt(), "entry 1 flash")
    }

    @Test
    fun `the default header is the overlay's own documented default`() {
        HudStateWriter.publish(listOf(HUD.HUDEntry(name = "WTap")))
        val buf = le(published())

        val flags = buf.getInt(12)
        assertTrue(flags and HudStateWriter.FLAG_WAVE != 0, "wave is on by default")
        assertTrue(flags and HudStateWriter.FLAG_WATERMARK != 0, "watermark is on by default")
        assertEquals(0, flags and HudStateWriter.FLAG_SPACED, "spaced is off by default")
        assertEquals(
            HudStateWriter.MODE_DEFAULT,
            (flags shr HudStateWriter.MODE_SHIFT) and 0b11,
            "mode default"
        )
        assertEquals(HudStateWriter.DEFAULT_PRIMARY, buf.getInt(16))
        assertEquals(HudStateWriter.DEFAULT_SECONDARY, buf.getInt(20))
        // 0 and "" mean "unknown": the overlay drops those watermark segments rather than drawing
        // a zero or an empty box.
        assertEquals(0, buf.getInt(24), "no fps yet")
        assertEquals("", readFixed(buf, 32, 24), "no nickname yet")
    }

    @Test
    fun `the mode value is masked into its two bits`() {
        // The reader drops bits 5..31, so a wild mode value must not be able to alias a flag or
        // leak into the reserved bits.
        fun flagsOf(mode: Int) = HudStateWriter.Header(
            wave = false, spaced = false, watermark = false, mode = mode
        ).flags()

        assertEquals(0, flagsOf(HudStateWriter.MODE_DEFAULT))
        assertEquals(1 shl HudStateWriter.MODE_SHIFT, flagsOf(HudStateWriter.MODE_RISE))
        assertEquals(2 shl HudStateWriter.MODE_SHIFT, flagsOf(HudStateWriter.MODE_VAPE))
        assertEquals(3 shl HudStateWriter.MODE_SHIFT, flagsOf(HudStateWriter.MODE_TESSERACT))
        assertEquals(3 shl HudStateWriter.MODE_SHIFT, flagsOf(0x7F), "masked, not shifted out")
        assertEquals(0, flagsOf(0x7F) and HudStateWriter.FLAG_WAVE, "no aliasing of the flags")
    }

    @Test
    fun `block arithmetic matches the contract`() {
        assertEquals(64, HudStateWriter.HEADER_SIZE)
        assertEquals(56, HudStateWriter.ENTRY_SIZE)
        assertEquals(3648, HudStateWriter.USED_SIZE)
        assertEquals(4096, HudStateWriter.STATE_SIZE)
        assertEquals(448, HudStateWriter.STATE_SIZE - HudStateWriter.USED_SIZE, "reserved trailer")
        assertEquals(3600, HudStateWriter.V1_STATE_SIZE, "the v1 block the reader still accepts")
    }

    @Test
    fun `stable ids and truncated names stay parseable`() {
        HudStateWriter.publish(listOf(HUD.HUDEntry(name = "A".repeat(60), value = "v".repeat(60))))
        val buf = le(published())
        assertEquals(1, buf.getInt(8))
        // Fixed fields must stay NUL terminated so the reader never runs past the field.
        val name = readFixed(buf, HudStateWriter.HEADER_SIZE + 8, 24)
        assertEquals(23, name.length)
        assertEquals("v".repeat(23), readFixed(buf, HudStateWriter.HEADER_SIZE + 32, 24))
    }

    @Test
    fun `a long nickname is truncated on a character boundary`() {
        // The field is 24 bytes; the writer must leave a NUL and never cut a UTF-8 character in
        // half, or the reader renders a replacement character in the watermark. 2-byte characters
        // put the 23-byte boundary one byte into a character — the case a byte-level trim gets
        // wrong by leaving a dangling lead byte.
        HudStateWriter.publish(emptyList(), HudStateWriter.Header(nickname = "ß".repeat(20)))
        val nickname = readFixed(le(published()), 32, 24)
        assertTrue(nickname.isNotEmpty() && nickname.length <= 23, "NUL terminated: $nickname")
        assertTrue(nickname.all { it == 'ß' }, "no half character: $nickname")

        // A 4-byte character (surrogate pair) must not be split either.
        HudStateWriter.publish(emptyList(), HudStateWriter.Header(nickname = "\uD83D\uDE00".repeat(8)))
        val emoji = readFixed(le(published()), 32, 24)
        assertTrue(emoji.isNotEmpty() && emoji.length % 2 == 0, "whole pairs only: $emoji")
        assertTrue(emoji.all { it == '\uD83D' || it == '\uDE00' }, "no half emoji: $emoji")
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 23, "fits the field: $emoji")
    }

    @Test
    fun `formatting codes are stripped from the published nickname`() {
        // mc.thePlayer.getName() is the display name and can carry a team colour code; the overlay
        // draws it as plain text.
        assertEquals("Steve", HudStateWriter.stripFormatting("§bSteve"))
        assertEquals("Steve", HudStateWriter.stripFormatting("§4§lSteve§r"))
        assertEquals("Steve", HudStateWriter.stripFormatting("Steve"))
        assertEquals("", HudStateWriter.stripFormatting("§c"))
        // A lone trailing marker has no character after it, so it is kept rather than swallowed.
        assertEquals("Steve§", HudStateWriter.stripFormatting("Steve§"))

        HudStateWriter.publish(emptyList(), HudStateWriter.Header(nickname = "§a§lNotch"))
        assertEquals("Notch", readFixed(le(published()), 32, 24))
    }

    @Test
    fun `the overlay mode choice maps onto the contract's bits`() {
        assertEquals(HudStateWriter.MODE_DEFAULT, HudStateWriter.modeCode("Default"))
        assertEquals(HudStateWriter.MODE_RISE, HudStateWriter.modeCode("Rise"))
        assertEquals(HudStateWriter.MODE_VAPE, HudStateWriter.modeCode("VapeV4"))
        assertEquals(HudStateWriter.MODE_VAPE, HudStateWriter.modeCode("vape"))
        assertEquals(HudStateWriter.MODE_TESSERACT, HudStateWriter.modeCode("Tesseract"))
        // A renamed or removed choice in a stale config must not take the overlay down.
        assertEquals(HudStateWriter.MODE_DEFAULT, HudStateWriter.modeCode("SomethingElse"))
    }

    private fun readFixed(buf: ByteBuffer, offset: Int, size: Int): String {
        val b = ByteArray(size)
        for (i in 0 until size) b[i] = buf.get(offset + i)
        val end = b.indexOf(0.toByte()).let { if (it < 0) size else it }
        return String(b, 0, end, Charsets.UTF_8)
    }
}
