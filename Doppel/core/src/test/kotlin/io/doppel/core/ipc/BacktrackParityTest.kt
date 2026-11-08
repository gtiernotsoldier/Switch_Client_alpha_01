package io.doppel.core.ipc

import io.doppel.core.strategy.player.BacktrackLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Backtrack parity: the Rust hold window against the Kotlin oracle.
 *
 * The clock in the fixture advances 50 ms per tick from a 1-second origin. That
 * origin matters: a port that keeps `holdStartNs == 0` as its "not holding"
 * sentinel passes every case here except the first tick of every hold, where it
 * reports a hold that began at the epoch.
 */
class BacktrackParityTest {

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
    fun `kotlin backtrack matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "backtrack_input.tsv"))
        val expected = rows(File(dir, "backtrack_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        val state = BacktrackLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val hold = BacktrackLogic.tick(
                state,
                BacktrackLogic.Input(
                    nowNs = row[1].toLong(),
                    modern = row[2] == "1",
                    pulse = row[3] == "1",
                    smart = row[4] == "1",
                    minDelayMs = row[5].toInt(),
                    maxDelayMs = row[6].toInt(),
                    distance = row[7].toFloat(),
                    legacyDistance = row[8].toFloat(),
                    mustRelease = row[9] == "1"
                )
            ) { _, _ -> row[10].toInt() }

            assertEquals(expected[i][1] == "1", hold, "hold at tick $i")
        }
    }

    @Test
    fun `the fixture exercises holds and releases`() {
        val dir = goldenDir()
        val rows = rows(File(dir, "backtrack_expected.tsv"))
        val holds = rows.count { it[1] == "1" }
        val gaps = rows.size - holds
        assert(holds > 50) { "fixture only holds $holds times" }
        assert(gaps > 50) { "fixture only releases $gaps times" }
    }
}
