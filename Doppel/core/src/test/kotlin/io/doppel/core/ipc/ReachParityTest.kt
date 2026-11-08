package io.doppel.core.ipc

import io.doppel.core.strategy.player.ReachLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Reach parity: the Rust press sampling against the Kotlin oracle.
 *
 * The fixture holds the randomness still (`chance == 100`, empty range), so what
 * it checks is the part a port gets wrong: the order of the gates. Moving the
 * throttle counter above the chance roll passes every "does it extend" case and
 * still changes how often the module fires.
 */
class ReachParityTest {

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
    fun `kotlin reach matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "reach_input.tsv"))
        val expected = rows(File(dir, "reach_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        val state = ReachLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val d = ReachLogic.sample(
                state,
                ReachLogic.Input(
                    nowNs = row[1].toLong(),
                    conditionsOk = row[2] == "1",
                    guiOpen = row[3] == "1",
                    chance = row[4].toInt(),
                    hitPer = row[5].toInt(),
                    delayMs = row[6].toInt(),
                    hitThroughBlocks = row[7] == "1",
                    lookingAtBlock = row[8] == "1",
                    reachMin = row[9].toFloat(),
                    reachMax = row[10].toFloat()
                ),
                rollChance = { error("the fixture never rolls the chance") },
                rollDistance = { error("the fixture uses an empty range") }
            )

            val want = java.lang.Double.longBitsToDouble(expected[i][1].toLong(16))
            assertEquals(
                java.lang.Double.doubleToLongBits(want),
                java.lang.Double.doubleToLongBits(d),
                "distance bits at row $i"
            )
        }
    }

    @Test
    fun `the fixture both extends and skips`() {
        val dir = goldenDir()
        val rows = rows(File(dir, "reach_expected.tsv"))
        val extended = rows.count { it[1] != "0000000000000000" }
        assert(extended > 50) { "fixture only extends $extended times" }
        assert(rows.size - extended > 50) { "fixture only skips ${rows.size - extended} times" }
    }
}
