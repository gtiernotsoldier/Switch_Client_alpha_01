package io.doppel.core.ipc

import io.doppel.core.strategy.player.AutoBlockSampleLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * AutoBlock chance-gate parity: the Rust machine against the Kotlin oracle.
 *
 * The fixture is a 500-row replay of configured chances, not independent
 * cases: the interesting failures are a roll consumed for `chance == 100`, a
 * skipped roll at `chance == 0`, or a verdict computed from a range other
 * than `nextInt(100)`. The `draws` column is what catches them.
 */
class AutoBlockSampleParityTest {

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
    fun `kotlin autoblock chance gate matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "autoblock_sample_input.tsv"))
        val expected = rows(File(dir, "autoblock_sample_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")
        assertEquals(500, inputs.size, "fixture must be the full replay")

        var full = 0
        var zero = 0
        for ((i, row) in inputs.withIndex()) {
            val chance = row[1].toInt()
            if (chance == 100) full++
            if (chance == 0) zero++
            val (passed, draws) = AutoBlockSampleLogic.sample(chance, intArrayOf(row[2].toInt()))

            val e = expected[i]
            assertEquals(i, e[0].toInt(), "idx at row $i")
            assertEquals(e[1].toInt() != 0, passed, "passed at row $i")
            assertEquals(e[2].toInt(), draws, "rolls consumed at row $i")
        }
        assertTrue(full > 0, "fixture must exercise chance == 100")
        assertTrue(zero > 0, "fixture must exercise chance == 0")
    }
}
