package io.doppel.adapter.common.hud

import io.doppel.adapter.common.module.render.HUD
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Publishes the module table for the external HUD process.
 *
 * Contract v3 (`#pragma pack(push,1)`, little-endian, 4096 bytes) — the layout the Rust reader in
 * `Doppel/hud/src/state.rs` parses, and the one the overlay checks before it draws anything. The
 * frozen specification is `Doppel/docs/hud-contract-v3.md`; this file and `state.rs` are the two
 * implementations of it:
 *
 * ```
 * offset size field
 *   0    4    uint32 magic     0x444F5050 ("DOPP")
 *   4    4    uint32 version   3
 *   8    4    uint32 count     entries written, <= 64
 *  12    4    uint32 flags     bit0 wave, bit1 spaced, bit2 watermark,
 *                              bits 3..5 mode (0 default, 1 rise, 2 vape, 3 tesseract, 4 modern),
 *                              bit6 draw rows in published order, bits 7..31 reserved
 *  16    4    uint32 primary   0x00RRGGBB
 *  20    4    uint32 secondary 0x00RRGGBB
 *  24    4    uint32 fps       0 = unknown, the watermark's FPS segment is dropped
 *  28    4    uint32 scale     scale x1000; 0 = unset (the overlay keeps its own default)
 *  32   24    char   nickname[24] UTF-8, NUL padded
 *  56    4    uint32 opacity   background opacity percent; 0 = unset (100%)
 *  60    4    uint32 line_spacing  row gap percent; 0 = unset (100%)
 *  60    4    uint32 reserved
 *  64 3584    entries[64]     56 bytes each
 * 3648  448    reserved trailer, so the block is exactly 4096 bytes
 * ```
 *
 * Entry, 56 bytes:
 * ```
 * uint32 id       stable hash of the module name (FNV-1a 32)
 * uint8  enabled  1 = currently enabled; the writer only ever publishes enabled modules
 * uint8  flash    work-pulse strength 0..255 — how red the row should be right now (v3)
 * uint8  highlight  draw the value in the accent colour
 * uint8  pad
 * char   name[24] UTF-8, NUL padded
 * char   value[24] UTF-8, NUL padded, empty = no value shown
 * ```
 *
 * v3 changed three things over v2 and nothing else: the mode field grew from two bits to three
 * (the Modern mode needs a fifth value), two reserved words became `scale` and `opacity`, and the
 * entry's `flash` byte stopped being the old "module is enabled, draw it red" flag and became a
 * decaying work-pulse strength (`Module.markWorked` / `flashStrength`). The reader still accepts
 * v1 and v2, so a stale file from an older build cannot break the overlay.
 *
 * Plain file IO is deliberate: the block is written only when its contents actually change (plus a
 * one-second keep-alive, because the overlay treats three seconds of silence as "the game is
 * gone"), so an idle HUD costs one hash per frame and no syscall at all. The reader is a separate
 * process that opens the same file read-only, so there is no back channel and nothing the HUD can
 * do to the game (plan §5.2).
 *
 * The file name is still a fixed one here; randomising it belongs to the plan's D6
 * de-characterisation step, together with the process/window naming.
 */
object HudStateWriter {

    const val MAGIC: Int = 0x444F5050 // "DOPP"

    /** The version this writer publishes. */
    const val VERSION: Int = 3

    /** Versions the reader still accepts, for a stale file from an older build. */
    const val V2_VERSION: Int = 2
    const val V1_VERSION: Int = 1

    const val MAX_MODULES: Int = 64
    const val ENTRY_SIZE: Int = 56

    /** The v3 header (v2 has the same size and a narrower mode field). */
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

    /** Shift of the three `ArrayListMode` bits. */
    const val MODE_SHIFT: Int = 3

    /** The mode field is three bits wide in v3 (two in v2). */
    const val MODE_MASK: Int = 0b111

    /** Draw the rows in the order they were published instead of width-sorted. */
    const val FLAG_ORDER_PUBLISHED: Int = 1 shl 6

    /**
     * Anchor the module list to the left edge.
     *
     * Bit 7 was reserved in v2 and v3, which is exactly what makes this a safe addition rather than a
     * protocol bump: a reader that does not know the bit already masks it off, and no other field's
     * meaning moved.
     */
    const val FLAG_POSITION_LEFT: Int = 1 shl 7

    /** `ArrayListMode` values (`ArrayList.h:14`, plus this port's Modern mode). */
    const val MODE_DEFAULT: Int = 0
    const val MODE_RISE: Int = 1
    const val MODE_VAPE: Int = 2
    const val MODE_TESSERACT: Int = 3
    const val MODE_MODERN: Int = 4

    /** The wave colour pair, the same defaults the overlay falls back to (ModuleConfig.h:65-66). */
    const val DEFAULT_PRIMARY: Int = 0x4E566B
    const val DEFAULT_SECONDARY: Int = 0x7892D6

    /** The `scale`/`opacity`/`line_spacing` words are 0 when the option is at the overlay's default. */
    const val NO_SCALE: Int = 0
    const val NO_OPACITY: Int = 0
    const val NO_LINE_SPACING: Int = 0

    private const val NAME_BYTES = 24
    private const val VALUE_BYTES = 24
    private const val NICKNAME_BYTES = 24

    /** The overlay's "the game is gone" rule is three seconds; one keeps it alive well inside that. */
    private const val KEEPALIVE_MS = 1_000L

    /**
     * The v3 header: everything in the block that is *not* a module row.
     *
     * `fps == 0` means "unknown" and the overlay drops the FPS segment; an empty `nickname` does the
     * same for the name segment. `scale == 0f` / `opacity == 0` mean "unset" — the overlay then
     * keeps its own default (or its command line), which is what lets the header be a default
     * rather than an override.
     */
    data class Header(
        val wave: Boolean = true,
        val spaced: Boolean = false,
        val watermark: Boolean = true,
        val mode: Int = MODE_DEFAULT,
        val primary: Int = DEFAULT_PRIMARY,
        val secondary: Int = DEFAULT_SECONDARY,
        val fps: Int = 0,
        val nickname: String = "",
        /** 0 = unset, else the overlay scale (0.5 .. 4.0). */
        val scale: Float = 0f,
        /** 0 = unset, else the row/panel background opacity percent (1 .. 100). */
        val opacity: Int = 0,
        /** 0 = unset, else the row gap as a percent of the mode's own (50 .. 200). */
        val lineSpacing: Int = 0,
        /** Draw the rows in publication order instead of width-sorted. */
        val orderPublished: Boolean = false,
        /** Anchor the module list to the left edge instead of the right one. */
        val positionLeft: Boolean = false
    ) {
        /** The `flags` word, with the mode clamped into its three bits. */
        fun flags(): Int =
            (if (wave) FLAG_WAVE else 0) or
                (if (spaced) FLAG_SPACED else 0) or
                (if (watermark) FLAG_WATERMARK else 0) or
                ((mode and MODE_MASK) shl MODE_SHIFT) or
                (if (orderPublished) FLAG_ORDER_PUBLISHED else 0) or
                (if (positionLeft) FLAG_POSITION_LEFT else 0)

        /** The `scale` word: the scale x1000, or 0 when unset. */
        fun scaleWord(): Int =
            if (scale <= 0f) NO_SCALE else (scale * 1000f).toInt()
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
        "modern" -> MODE_MODERN
        else -> MODE_DEFAULT
    }

    private val buffer: ByteBuffer = ByteBuffer.allocate(STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    @Volatile
    private var file: RandomAccessFile? = null

    /** Publishes so far — diagnostics only. */
    @Volatile
    var publishCount: Long = 0L
        private set

    /** Writes actually issued, after the change detection — diagnostics only. */
    @Volatile
    var writeCount: Long = 0L
        private set

    /** Hash of the last block written, and when: the idle path skips the syscall. */
    private var lastHash: Long = 0L
    private var lastWriteMs: Long = 0L

    /** %TEMP%\<name>.state. Fixed name until the D6 pass randomises it. */
    fun defaultFile(): File = File(System.getProperty("java.io.tmpdir"), "doppel-hud.state")

    /**
     * Pack [entries] and the v3 [header] and write the block. Never throws: the HUD is cosmetic and
     * must not be able to break the game's render loop.
     *
     * [flashOf] is asked for each row's work-pulse strength (0..255) at publish time, because that
     * value decays with the wall clock and cannot be cached in the row list. The default publishes
     * a dark row, so a caller that does not care about the flash keeps the old one-argument call.
     */
    fun publish(
        entries: List<HUD.HUDEntry>,
        header: Header = Header(),
        flashOf: (String) -> Int = { 0 }
    ) {
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
            buffer.putInt(header.scaleWord())
            putFixedString(buffer, stripFormatting(header.nickname), NICKNAME_BYTES)
            buffer.putInt(header.opacity.coerceIn(0, 100))
            buffer.putInt(header.lineSpacing.coerceIn(0, 200))

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
                    buffer.put(flashOf(e.name).coerceIn(0, 255).toByte()) // work pulse, 0 = idle
                    buffer.put(if (e.highlight) 1 else 0) // highlight: accent-coloured value
                    buffer.put(0) // pad
                    putFixedString(buffer, e.name, NAME_BYTES)
                    putFixedString(buffer, e.value, VALUE_BYTES)
                }
            }

            // The trailer between USED_SIZE and STATE_SIZE stays zero: cleared once at allocation
            // and never written, so the whole block is still written in one call.
            val bytes = buffer.array()
            val now = System.currentTimeMillis()
            val hash = hashOf(bytes)
            // An unchanged block is not worth a syscall — but the overlay's liveness signal is the
            // file's mtime, so an idle game still gets one write a second.
            if (hash == lastHash && now - lastWriteMs < KEEPALIVE_MS) {
                publishCount++
                return
            }
            out.seek(0L)
            out.write(bytes, 0, STATE_SIZE)
            lastHash = hash
            lastWriteMs = now
            publishCount++
            writeCount++
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

    /**
     * FNV-1a over the used part of the block. 3648 byte steps per frame is a few microseconds —
     * far cheaper than the 4 KB write it usually avoids — and a collision only costs one skipped
     * repaint of an identical frame.
     */
    private fun hashOf(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL // 0xCBF29CE484222325
        for (i in 0 until USED_SIZE) {
            hash = hash xor (bytes[i].toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
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
