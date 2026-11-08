package io.doppel.core.ipc

import io.doppel.core.strategy.player.EaglePlaceLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Eagle AutoPlace parity: the Rust placement machine against the Kotlin oracle.
 *
 * The fixture is a single 320-tick replay, not independent cases: the machine
 * is stateful, and the interesting failures (a cooldown drawn on an idle tick,
 * an aim that keeps counting through a gate close, a click that never arms a
 * fresh cooldown) only appear in sequence.
 *
 * The cooldown roll is scripted per row rather than being drawn here: Pcg32 and
 * `kotlin.random` are different generators, so comparing them would test the
 * RNG, not the machine. The column that *does* catch a porting mistake is
 * `rolls` — a port that draws while cooling down or aiming produces the same
 * steps and the wrong stream position.
 */
class EaglePlaceParityTest {

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
    fun `kotlin eagle place matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "eagle_place_input.tsv"))
        val expected = rows(File(dir, "eagle_place_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = EaglePlaceLogic.State()

        for ((i, row) in inputs.withIndex()) {
            var rolls = 0
            val d = EaglePlaceLogic.tick(
                state,
                EaglePlaceLogic.Input(
                    gatesOpen = row[1] == "1",
                    pitchInTolerance = row[2] == "1",
                    bridgeAssistEnabled = row[3] == "1",
                    maxWaitTicks = row[4].toInt(),
                    cooldownMin = row[5].toInt(),
                    cooldownMax = row[6].toInt()
                )
            ) { rolls++; row[7].toInt() }

            val e = expected[i]
            assertEquals(e[1].toInt(), d.step.code, "step at tick $i")
            assertEquals(e[2] == "1", d.state.aiming, "aiming at tick $i")
            assertEquals(e[3].toInt(), d.state.aimTicks, "aim ticks at tick $i")
            assertEquals(e[4].toInt(), d.state.cooldownTicks, "cooldown at tick $i")
            assertEquals(e[5].toInt(), rolls, "rolls consumed at tick $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every branch and the roll discipline`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "eagle_place_input.tsv"))
        val expected = rows(File(dir, "eagle_place_expected.tsv"))

        val steps = expected.groupingBy { it[1] }.eachCount()
        val gateClosed = inputs.count { it[1] == "0" }
        val bridgeAssist = inputs.count { it[3] == "1" }
        val clicks = expected.count { it[1] == "2" }
        val rolled = expected.count { it[5] != "0" }

        assert((steps["0"] ?: 0) > 10) { "fixture rarely idles" }
        assert((steps["1"] ?: 0) > 10) { "fixture rarely aims" }
        assert((steps["2"] ?: 0) > 10) { "fixture rarely clicks" }
        assert((steps["3"] ?: 0) > 5) { "fixture rarely aborts" }
        assert(gateClosed > 5) { "fixture rarely closes the gates" }
        assert(bridgeAssist > 5) { "fixture rarely sees BridgeAssist own the view" }
        assertEquals(clicks, rolled, "exactly one cooldown draw per click")
    }
}
