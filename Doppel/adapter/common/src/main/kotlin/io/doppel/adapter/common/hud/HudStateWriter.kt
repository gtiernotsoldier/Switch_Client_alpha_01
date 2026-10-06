package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.render.HUD
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Publishes the module table for the external HUD process.
 *
 * Contract v2 (`#pragma pack(push,1)`, little-endian, 4096 bytes) — the layout the Rust reader in
 * `Doppel/hud/src/state.rs` parses, and the one the overlay checks before it draws anything:
 *
 * ```
 * offset size field
 *   0    4    uint32 magic     0x444F5050 ("DOPP")
 *   4    4    uint32 version   2
 *   8    4    uint32 count     entries written, <= 64
 *  12    4    uint32 flags     bit0 wave, bit1 spaced, bit2 watermark,
 *                              bits 3..4 mode (0 default, 1 rise, 2 vape, 3 tesseract),
 *                              bits 5..31 reserved
 *  16    4    uint32 primary   0x00RRGGBB
 *  20    4    uint32 secondary 0x00RRGGBB
 *  24    4    uint32 fps       0 = unknown, the watermark's FPS segment is dropped
 *  28    4    uint32 reserved
 *  32   24    char   nickname[24] UTF-8, NUL padded
 *  56    4    uint32 reserved
 *  60    4    uint32 reserved
 *  64 3584    entries[64]     56 bytes each
 * 3648  448    reserved trailer, so the block is exactly 4096 bytes
 * ```
 *
 * Entry, 56 bytes:
 * ```
 * uint32 id       stable hash of the module name (FNV-1a 32)
 * uint8  enabled
 * uint8  flash    the module worked recently; the HUD decays this itself (plan §4.6)
 * uint8  highlight  draw the value in the accent colour (old card's hudHighlight)
 * uint8  pad
 * char   name[24] UTF-8, NUL padded
 * char   value[24] UTF-8, NUL padded, empty = no value shown
 * ```
 *
 * v2 is a superset: the header grew from 16 to 64 bytes and the block from 3600 to 4096, but the
 * entries are byte-for-byte the v1 entry, so a reader that knows v1 keeps working on an old file and
 * `HudStateWriter` no longer writes one. The overlay still *accepts* v1 (`V1_VERSION`) because a
 * stale file may be lying around from a previous build.
 *
 * Plain file IO is deliberate for the first cut (plan §5.4): 4 KB per frame costs ~10 us and needs
 * no JNI/FFM glue. The reader is a separate process that opens the same file read-only, so there is
 * no back channel and nothing the HUD can do to the game (plan §5.2).
 *
 * The file name is still a fixed one here; randomising it belongs to the plan's D6
 * de-characterisation step, together with the process/window naming.
 */
object HudStateWriter {

    const val MAGIC: Int = 0x444F5050 // "DOPP"

    /** The version this writer publishes. */
    const val VERSION: Int = 2

    /** The original block, still accepted by the reader. */
    const val V1_VERSION: Int = 1

    const val MAX_MODULES: Int = 64
    const val ENTRY_SIZE: Int = 56

    /** The v2 header. */
    const val HEADER_SIZE: Int = 64

    /** The v1 header: `magic`, `version`, `count`, `pad`. */
    const val V1_HEADER_SIZE: Int = 16

    /** Header + entries. Nothing past this offset is part of the contract. */
    const val USED_SIZE: Int = HEADER_SIZE + MAX_MODULES * ENTRY_SIZE // 3648

    /** The declared block: `USED_SIZE` plus a 448-byte reserved trailer. */
    const val STATE_SIZE: Int = 4096

    /** A v1 block, entries included — what a previous build left on disk. */
    const val V1_STATE_SIZE: Int = V1_HEADER_SIZE + MAX_MODULES * ENTRY_SIZE // 3600

    // ── the `flags` word, mirrored bit for bit by hud/src/state.rs ──

    /** `m_Wave` — the Default mode's animation. */
    const val FLAG_WAVE: Int = 1 shl 0

    /** `m_SpacedModules`. */
    const val FLAG_SPACED: Int = 1 shl 1

    /** `m_Watermark`. */
    const val FLAG_WATERMARK: Int = 1 shl 2

    /** Shift of the two `ArrayListMode` bits. */
    const val MODE_SHIFT: Int = 3

    /** `ArrayListMode` values (`ArrayList.h:14`). */
    const val MODE_DEFAULT: Int = 0
    const val MODE_RISE: Int = 1
    const val MODE_VAPE: Int = 2
    const val MODE_TESSERACT: Int = 3

    /** The wave colour pair, the same defaults the overlay falls back to (ModuleConfig.h:65-66). */
    const val DEFAULT_PRIMARY: Int = 0x4E566B
    const val DEFAULT_SECONDARY: Int = 0x7892D6

    private const val NAME_BYTES = 24
    private const val VALUE_BYTES = 24
    private const val NICKNAME_BYTES = 24

    /**
     * The v2 header: everything in the block that is *not* a module row.
     *
     * `fps == 0` means "unknown" and the overlay drops the FPS segment; an empty `nickname` does the
     * same for the name segment. Both are cosmetic on purpose — a game that cannot answer must not
     * make the overlay show a wrong number.
     */
    data class Header(
        val wave: Boolean = true,
        val spaced: Boolean = false,
        val watermark: Boolean = true,
        val mode: Int = MODE_DEFAULT,
        val primary: Int = DEFAULT_PRIMARY,
        val secondary: Int = DEFAULT_SECONDARY,
        val fps: Int = 0,
        val nickname: String = ""
    ) {
        /** The `flags` word, with the mode clamped into its two bits. */
        fun flags(): Int =
            (if (wave) FLAG_WAVE else 0) or
                (if (spaced) FLAG_SPACED else 0) or
                (if (watermark) FLAG_WATERMARK else 0) or
                ((mode and 0b11) shl MODE_SHIFT)
    }

    /**
     * The HUD module's `OverlayMode` choice name → the header's mode bits.
     *
     * Unknown names fall back to Default rather than throwing: a renamed option in a stale config
     * file must not take the overlay down.
     */
    fun modeCode(choice: String): Int = when (choice.trim().lowercase()) {
        "rise" -> MODE_RISE
        "vape", "vapev4" -> MODE_VAPE
        "tesseract" -> MODE_TESSERACT
        else -> MODE_DEFAULT
    }

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
     * Pack [entries] and the v2 [header] and write the block. Never throws: the HUD is cosmetic and
     * must not be able to break the game's render loop.
     */
    fun publish(entries: List<HUD.HUDEntry>, header: Header = Header()) {
        try {
            val out = file ?: open() ?: return
            val count = minOf(entries.size, MAX_MODULES)

            buffer.clear()
            buffer.putInt(MAGIC)
            buffer.putInt(VERSION)
            buffer.putInt(count)
            buffer.putInt(header.flags())
            // 0x00RRGGBB: the high byte is not part of the contract, so an ARGB value from a colour
            // picker is masked rather than written through as an alpha the reader would ignore.
            buffer.putInt(header.primary and 0xFFFFFF)
            buffer.putInt(header.secondary and 0xFFFFFF)
            buffer.putInt(header.fps.coerceAtLeast(0))
            buffer.putInt(0) // reserved
            putFixedString(buffer, stripFormatting(header.nickname), NICKNAME_BYTES)
            buffer.putInt(0) // reserved
            buffer.putInt(0) // reserved

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
                    buffer.put(if (e.highlight) 1 else 0) // highlight: accent-coloured value
                    buffer.put(0) // pad
                    putFixedString(buffer, e.name, NAME_BYTES)
                    putFixedString(buffer, e.value, VALUE_BYTES)
                }
            }

            // The trailer between USED_SIZE and STATE_SIZE stays zero: cleared once at allocation
            // and never written, so the whole block is still written in one call.
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

    /**
     * UTF-8 into a fixed field, truncated on a character boundary and NUL padded.
     *
     * The prefix is measured in code points *before* encoding, rather than encoding the whole
     * string and trimming bytes: cutting the byte array leaves a dangling UTF-8 lead byte whenever
     * the boundary falls one byte into a multi-byte character (which is the common case for the
     * 24-byte nickname and name fields), and the reader then renders U+FFFD. A surrogate pair is
     * stepped over as one unit so an emoji cannot be split either.
     */
    private fun putFixedString(buf: ByteBuffer, text: String?, size: Int) {
        if (text.isNullOrEmpty()) {
            buf.put(ByteArray(size))
            return
        }
        val max = size - 1 // one byte stays for the NUL terminator
        var end = 0
        var used = 0
        while (end < text.length) {
            val c = text[end]
            val charLen = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && end + 1 < text.length &&
                    Character.isLowSurrogate(text[end + 1]) -> 4
                else -> 3 // U+0800..U+FFFF, and lone surrogates (encoded as 3 replacement bytes)
            }
            if (used + charLen > max) break
            used += charLen
            end += if (charLen == 4) 2 else 1
        }
        val bytes = text.substring(0, end).toByteArray(Charsets.UTF_8)
        buf.put(bytes)
        buf.put(ByteArray(size - bytes.size))
    }

    /**
     * Drop Minecraft's `§x` formatting codes.
     *
     * `mc.thePlayer.getName()` is the *display* name and routinely carries a colour code (servers
     * set it from the scoreboard team). The overlay draws the nickname as plain text, so a raw §
     * would be rendered as a stray glyph in the watermark.
     */
    fun stripFormatting(text: String): String {
        if (text.indexOf('§') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '§' && i + 1 < text.length) {
                i += 2 // skip the code AND the character it formats
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }
}
