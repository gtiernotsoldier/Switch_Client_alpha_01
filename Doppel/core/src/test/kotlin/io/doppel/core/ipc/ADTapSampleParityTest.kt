package io.doppel.core.ipc

import io.doppel.core.strategy.player.ADTapSampleLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ADTap range-sampling parity: the Rust machine against the Kotlin oracle.
 *
 * The fixture is a 500-row replay of configured values, not independent cases:
 * the interesting failures are a chance roll consumed for `chance == 100`, a
 * gap drawn where the duration belongs (or vice versa), a `min == max` range
 * that still advances the stream, or a fail path that skips its retry gap.
 * The `draws` column is what catches them.
 */
class ADTapSampleParityTest {

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
    fun `kotlin adtap sampling matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "adtap_sample_input.tsv"))
        val expected = rows(File(dir, "adtap_sample_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")
        assertEquals(500, inputs.size, "fixture must be the full replay")

        for ((i, row) in inputs.withIndex()) {
            val (out, draws) = ADTapSampleLogic.sample(
                ADTapSampleLogic.Input(
                    kind = row[1].toInt(),
                    chancePercent = row[2].toInt(),
                    tapMinMs = row[3].toInt(),
                    tapMaxMs = row[4].toInt(),
                    gapMinMs = row[5].toInt(),
                    gapMaxMs = row[6].toInt()
                ),
                intArrayOf(row[7].toInt(), row[8].toInt())
            )

            val e = expected[i]
            assertEquals(i, e[0].toInt(), "idx at row $i")
            assertEquals(e[1].toInt(), ADTapSampleLogic.pack(out), "packed code at row $i")
            assertEquals(e[2].toInt(), out.passed, "passed at row $i")
            assertEquals(e[3].toInt(), out.durationMs, "duration at row $i")
            assertEquals(e[4].toInt(), out.gapMs, "gap at row $i")
            assertEquals(e[5].toInt(), draws, "rolls consumed at row $i")
        }
    }
}
