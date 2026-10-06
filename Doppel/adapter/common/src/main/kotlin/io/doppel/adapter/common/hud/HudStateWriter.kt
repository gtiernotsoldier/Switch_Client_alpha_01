package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.render.HUD
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Publishes the module table for the external HUD process.
 *
 * Contract (HUD externalisation plan §5.3, `#pragma pack(push,1)`, 3600 bytes, little-endian):
 *
 * ```
 * uint32 magic      0x444F5050 ("DOPP")
 * uint32 version    protocol version — a reader with a different version refuses to draw
 * uint32 count      valid entries
 * uint32 pad
 * ModuleEntry[64]   56 bytes each:
 *   uint32 id       stable hash of the module name
 *   uint8  enabled
 *   uint8  flash    the module worked recently; the HUD decays this itself (plan §4.6)
 *   uint8  highlight  pad[0]: draw the value in the accent colour (old card's hudHighlight)
 *   uint8  pad[1]
 *   char   name[24] UTF-8, NUL padded
 *   char   value[24] UTF-8, NUL padded, empty = no value shown
 * ```
 *
 * The plan's `pad[2]` is used for the highlight flag rather than adding a field, so the block stays
 * exactly 3600 bytes and the documented layout still holds byte for byte.
 *
 * Plain file IO is deliberate for the first cut (plan §5.4): 3.6 KB per frame costs ~10 us and needs
 * no JNI/FFM glue. The reader is a separate process that opens the same file read-only, so there is
 * no back channel and nothing the HUD can do to the game (plan §5.2).
 *
 * The file name is still a fixed one here; randomising it belongs to the plan's D6
 * de-characterisation step, together with the process/window naming.
 */
object HudStateWriter {

    const val MAGIC: Int = 0x444F5050 // "DOPP"
    const val VERSION: Int = 1
    const val MAX_MODULES: Int = 64
    const val ENTRY_SIZE: Int = 56
    const val HEADER_SIZE: Int = 16
    const val STATE_SIZE: Int = HEADER_SIZE + MAX_MODULES * ENTRY_SIZE // 3600

    private const val NAME_BYTES = 24
    private const val VALUE_BYTES = 24

    private val buffer: ByteBuffer = ByteBuffer.allocate(STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    @Volatile
    private var file: RandomAccessFile? = null

    /** Publishes so far — diagnostics only. */
    @Volatile
    var publishCount: Long = 0L
        private set

    /** %TEMP%\<name>.state. Fixed name until the D6 pass randomises it. */
    fun defaultFile(): File = File(System.getProperty("java.io.tmpdir"), "doppel-hud.state")

    /**
     * Pack [entries] and write the block. Never throws: the HUD is cosmetic and must not be able to
     * break the game's render loop.
     */
    fun publish(entries: List<HUD.HUDEntry>) {
        try {
            val out = file ?: open() ?: return
            val count = minOf(entries.size, MAX_MODULES)

            buffer.clear()
            buffer.putInt(MAGIC)
            buffer.putInt(VERSION)
            buffer.putInt(count)
            buffer.putInt(0) // pad

            for (i in 0 until MAX_MODULES) {
                val e = entries.getOrNull(i)
                if (e == null) {
                    buffer.putInt(0)
                    buffer.put(0)
                    buffer.put(0)
                    buffer.put(0)
                    buffer.put(0)
                    buffer.put(ByteArray(NAME_BYTES))
                    buffer.put(ByteArray(VALUE_BYTES))
                } else {
                    buffer.putInt(idOf(e.name))
                    buffer.put(1) // enabled: HUD.hudEntries only ever lists enabled modules
                    buffer.put(if (e.isRed) 1 else 0) // flash (the HUD decays it) — see §4.6
                    buffer.put(if (e.highlight) 1 else 0) // pad[0]: accent-coloured value
                    buffer.put(0) // pad[1]
                    putFixedString(buffer, e.name, NAME_BYTES)
                    putFixedString(buffer, e.value, VALUE_BYTES)
                }
            }

            out.seek(0L)
            out.write(buffer.array(), 0, STATE_SIZE)
            publishCount++
        } catch (_: Exception) {
            // Cosmetic channel: swallow everything (a missing HUD must never surface in-game).
        }
    }

    private fun open(): RandomAccessFile? {
        return try {
            val f = defaultFile()
            f.parentFile?.mkdirs()
            val raf = RandomAccessFile(f, "rw")
            raf.setLength(STATE_SIZE.toLong())
            file = raf
            raf
        } catch (_: Exception) {
            null
        }
    }

    /** FNV-1a 32-bit — stable across JVM runs, so the HUD can key per-module animation on it. */
    private fun idOf(name: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (b in name.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }

    /** UTF-8 into a fixed field, truncated on a character boundary and NUL padded. */
    private fun putFixedString(buf: ByteBuffer, text: String?, size: Int) {
        if (text.isNullOrEmpty()) {
            buf.put(ByteArray(size))
            return
        }
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > size - 1) bytes = bytes.copyOf(size - 1)
        // Do not cut a multi-byte character in half: trim trailing continuation bytes.
        var n = bytes.size
        while (n > 0 && (bytes[n - 1].toInt() and 0xC0) == 0x80) n--
        buf.put(bytes, 0, n)
        buf.put(ByteArray(size - n))
    }
}
