package io.doppel.core.ipc

import io.doppel.core.strategy.player.ClickAssistLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ClickAssist parity: the Rust gate order against the Kotlin oracle.
 *
 * The fixture is a stream because the "above 5 CPS" gate reads the module's own
 * 500 ms edge history. A port that records the edge after the gates, or trims
 * the window differently, passes every single-press case and fails here on the
 * first burst.
 */
class ClickAssistParityTest {

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
    fun `kotlin click assist matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "click_assist_input.tsv"))
        val expected = rows(File(dir, "click_assist_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        val state = ClickAssistLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val extra = ClickAssistLogic.evaluate(
                state,
                ClickAssistLogic.Input(
                    nowNs = row[1].toLong(),
                    flags = row[2].toInt(),
                    probability = row[3].toInt(),
                    mode = ClickAssistLogic.Mode.fromOrdinal(row[4].toInt())
                ),
                rollChance = { error("the fixture always passes the roll") },
                rollExtra = { error("the fixture never uses Random mode") }
            )
            assertEquals(expected[i][1].toInt(), extra, "extra clicks at row $i")
        }
    }

    @Test
    fun `the fixture both boosts and rejects`() {
        val dir = goldenDir()
        val rows = rows(File(dir, "click_assist_expected.tsv"))
        val boosted = rows.count { it[1] != "0" }
        assert(boosted > 50) { "fixture only boosts $boosted times" }
        assert(rows.size - boosted > 50) { "fixture only rejects ${rows.size - boosted} times" }
    }
}
