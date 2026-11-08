package io.doppel.core.ipc

import io.doppel.core.strategy.player.KeepSprintLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * KeepSprint parity: the Rust hold machine against the Kotlin oracle.
 *
 * The fixture is a single 500-frame replay, not independent cases: the
 * machine is stateful and the interesting failures (a hold released one
 * dispatch early, a latch that expired a frame late, an assert that
 * consumed the wrong tick, a release and an assert sharing a frame) only
 * appear in sequence.
 *
 * There is no randomness, so there is no roll column to script — the
 * machine is a pure function of the inputs, and the columns that catch a
 * porting mistake are the packed answer and the four state fields.
 *
 * `code` is packed: low nibble = the hold *status* (0 dead, 1 live,
 * 2 release), high nibble = the assert step. The hold nibble is compared
 * as part of the whole integer so a port that reports LIVE where the core
 * said DEAD (or vice versa) is caught even when the adapter would have
 * written nothing either way.
 */
class KeepSprintParityTest {

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
    fun `kotlin keep sprint matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "keep_sprint_input.tsv"))
        val expected = rows(File(dir, "keep_sprint_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = KeepSprintLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val d = KeepSprintLogic.frame(
                state,
                KeepSprintLogic.Input(
                    nowNs = row[1].toLong(),
                    tick = row[2].toInt(),
                    localSprinting = row[3] == "1",
                    attacking = row[4] == "1",
                    targetAlive = row[5] == "1",
                    forward = row[6] == "1",
                    forwardOverride = row[7] == "1"
                )
            )

            val e = expected[i]
            assertEquals(e[1].toInt(), d.code, "packed step at frame $i")
            assertEquals(e[2].toInt(), d.state.holdUntilTick, "hold window at frame $i")
            assertEquals(e[3].toLong(), d.state.holdDeadlineNs, "hold deadline at frame $i")
            assertEquals(e[4].toInt(), d.state.lastHandledTick, "handled tick at frame $i")
            assertEquals(e[5].toLong(), d.state.lastSprintSeenNs, "sprint latch at frame $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every hold status, asserts and the latch`() {
        val dir = goldenDir()
        val expected = rows(File(dir, "keep_sprint_expected.tsv"))

        val holds = expected.groupingBy { it[1].toInt() and 0xF }.eachCount()
        val asserts = expected.groupingBy { (it[1].toInt() shr 4) and 0xF }.eachCount()
        val latched = expected.count { it[5] != "0" }

        assert((holds[1] ?: 0) > 20) { "fixture rarely holds sprint across a tick" }
        assert((holds[2] ?: 0) > 10) { "fixture rarely releases a hold" }
        assert((holds[0] ?: 0) > 20) { "fixture rarely reports a dead hold" }
        assert((asserts[1] ?: 0) > 10) { "fixture rarely asserts" }
        assert(latched > 100) { "fixture rarely feeds the sprint latch" }

        // An assert may only happen with the target alive, forward held and
        // no tap override — a port that drops one of those gates shows up
        // here as an assert on a frame that should have stayed quiet.
        val inputs = rows(File(dir, "keep_sprint_input.tsv"))
        inputs.indices.forEach { i ->
            if (((expected[i][1].toInt() shr 4) and 0xF) == 1) {
                assert(inputs[i][5] == "1") { "an assert with a dead target at frame $i" }
                assert(inputs[i][6] == "1") { "an assert with forward released at frame $i" }
                assert(inputs[i][7] == "0") { "an assert while a tap owned W at frame $i" }
            }
        }
    }
}
