package io.doppel.core.ipc

import io.doppel.core.strategy.player.BlockHitSampleLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * BlockHit range-sampling parity: the Rust machine against the Kotlin oracle.
 *
 * The fixture is a 500-row replay of configured values, not independent
 * cases: the interesting failures are the two fire draws swapped (wait drawn
 * where the post-delay belongs), a `min == max` range that still advances the
 * stream, or an INIT threshold that lost its `coerceAtLeast(1)` clamp. The
 * `draws` column is what catches them.
 */
class BlockHitSampleParityTest {

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
    fun `kotlin blockhit sampling matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "blockhit_sample_input.tsv"))
        val expected = rows(File(dir, "blockhit_sample_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")
        assertEquals(500, inputs.size, "fixture must be the full replay")

        for ((i, row) in inputs.withIndex()) {
            val (out, draws) = BlockHitSampleLogic.sample(
                BlockHitSampleLogic.Input(
                    kind = row[1].toInt(),
                    postDelayMin = row[2].toInt(),
                    postDelayMax = row[3].toInt(),
                    waitMin = row[4].toInt(),
                    waitMax = row[5].toInt(),
                    hitPerMin = row[6].toInt(),
                    hitPerMax = row[7].toInt()
                ),
                intArrayOf(row[8].toInt(), row[9].toInt())
            )

            val e = expected[i]
            assertEquals(i, e[0].toInt(), "idx at row $i")
            assertEquals(e[1].toInt(), BlockHitSampleLogic.pack(out), "packed code at row $i")
            assertEquals(e[2].toInt(), out.postDelayMs, "post-delay at row $i")
            assertEquals(e[3].toInt(), out.waitMs, "wait at row $i")
            assertEquals(e[4].toInt(), out.threshold, "threshold at row $i")
            assertEquals(e[5].toInt(), draws, "rolls consumed at row $i")
        }
    }
}
