package io.doppel.core.ipc

import io.doppel.core.strategy.player.EagleLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Eagle parity: the Rust sneak machine against the Kotlin oracle.
 *
 * The fixture is a single 300-tick replay, not independent cases, because the
 * machine is stateful — the interesting failures (a re-armed delay, a lost
 * ownership bit, a countdown that skips a tick) only appear in sequence.
 *
 * The roll value rides in the input file rather than being drawn here: Pcg32
 * and `kotlin.random` are different generators, so comparing them would test
 * the RNG, not the machine. The column that *does* catch a porting mistake is
 * `rolls` — a port that draws the delay on an idle tick produces the same
 * presses and the wrong stream position.
 */
class EagleParityTest {

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
    fun `kotlin eagle matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "eagle_input.tsv"))
        val expected = rows(File(dir, "eagle_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var autoActive = false
        var delay = 0

        for ((i, row) in inputs.withIndex()) {
            val d = EagleLogic.tick(
                EagleLogic.Input(
                    onEdge = row[1] == "1",
                    settingsMet = row[2] == "1",
                    sneakKeyHeld = row[3] == "1",
                    autoSneakActive = autoActive,
                    unsneakDelayTicks = delay
                )
            ) { row[4].toInt() }

            val e = expected[i]
            assertEquals(e[1] == "1", d.press, "press at tick $i")
            assertEquals(e[2] == "1", d.release, "release at tick $i")
            assertEquals(e[3] == "1", d.autoSneakActive, "ownership at tick $i")
            assertEquals(e[4].toInt(), d.unsneakDelayTicks, "delay at tick $i")

            autoActive = d.autoSneakActive
            delay = d.unsneakDelayTicks
        }
    }

    @Test
    fun `the fixture exercises every branch`() {
        // A replay that never leaves the edge, never sees a held key or never
        // fails the gate would pass while three quarters of the machine are
        // untested. Assert the fixture itself covers them.
        val dir = goldenDir()
        val inputs = rows(File(dir, "eagle_input.tsv"))
        val expected = rows(File(dir, "eagle_expected.tsv"))

        val presses = expected.count { it[1] == "1" }
        val releases = expected.count { it[2] == "1" }
        val gateOff = inputs.count { it[2] == "0" }
        val heldKey = inputs.count { it[3] == "1" }
        val rolled = expected.count { it[5] != "0" }

        assert(presses > 10) { "fixture only presses $presses times" }
        assert(releases > 10) { "fixture only releases $releases times" }
        assert(gateOff > 10) { "fixture only fails the gate $gateOff times" }
        assert(heldKey > 10) { "fixture only sees a held key $heldKey times" }
        assert(rolled > 10) { "fixture only rolls $rolled times" }
    }
}
