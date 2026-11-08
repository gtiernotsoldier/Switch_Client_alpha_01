package io.doppel.core.ipc

import io.doppel.core.strategy.player.KnockbackDisplaceLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * KnockbackDisplace parity: the Rust flick against the Kotlin oracle.
 *
 * Offsets are compared as raw f32 bits. The value is `sign * angle`, and a port
 * that multiplies in a different order — or promotes through f64 on the way —
 * lands one ulp away while still "looking" like 90 degrees.
 */
class KnockbackDisplaceParityTest {

    private fun goldenDir(): File {
        val candidates = listOf(
            File("../core-rs/golden"),
            File("Doppel/core-rs/golden"),
            File("core-rs/golden")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("golden fixtures not found. Run: cd Doppel/core-rs && cargo run -- --emit-golden golden")
    }

    private fun rows(f: File): List<Array<String>> =
        f.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it[0].isDigit() }
            .map { it.split("\t").toTypedArray() }

    @Test
    fun `kotlin flick matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "kbd_input.tsv"))
        val expected = rows(File(dir, "kbd_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        val state = KnockbackDisplaceLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val offset = KnockbackDisplaceLogic.attack(
                state,
                KnockbackDisplaceLogic.Input(
                    nowNs = row[1].toLong(),
                    direction = KnockbackDisplaceLogic.Direction.fromOrdinal(row[2].toInt()),
                    useLastStrafe = row[3] == "1",
                    cooldownMs = row[4].toInt(),
                    angle = row[5].toFloat(),
                    notTakingKnockback = row[6] == "1",
                    velocityThisTick = row[7] == "1",
                    keyLeft = row[8] == "1",
                    keyRight = row[9] == "1"
                )
            ) { 1f } // the fixture never selects Random

            val want = java.lang.Float.intBitsToFloat(expected[i][1].toLong(16).toInt())
            assertEquals(
                java.lang.Float.floatToIntBits(want),
                java.lang.Float.floatToIntBits(offset),
                "offset bits at row $i (want $want, got $offset)"
            )
        }
    }

    @Test
    fun `the fixture produces flicks in both directions and some vetoes`() {
        val dir = goldenDir()
        val rows = rows(File(dir, "kbd_expected.tsv"))
        val vals = rows.map { java.lang.Float.intBitsToFloat(it[1].toLong(16).toInt()) }
        val positive = vals.count { it > 0f }
        val negative = vals.count { it < 0f }
        val zero = vals.count { it == 0f }
        assert(positive > 20) { "only $positive positive flicks" }
        assert(negative > 20) { "only $negative negative flicks" }
        assert(zero > 20) { "only $zero vetoed/blocked attacks" }
    }
}
