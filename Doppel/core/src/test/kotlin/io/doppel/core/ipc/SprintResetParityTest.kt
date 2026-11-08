package io.doppel.core.ipc

import io.doppel.core.strategy.player.SprintResetLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SprintReset parity: the Rust rising-edge / throttle / delay machine against
 * the Kotlin oracle.
 *
 * The fixture is a single 400-tick replay, not independent cases: the machine
 * is stateful, and the interesting failures (a chance roll on a throttled hit,
 * a delayed send that fires one tick early, a target switch that does not
 * reset the throttle) only appear in sequence.
 *
 * The chance roll is scripted per row rather than drawn here: Pcg32 and
 * `kotlin.random` are different generators, so comparing them would test the
 * RNG, not the machine. The column that catches a porting mistake is `rolls` —
 * a port that draws while idle or throttled produces the same steps and the
 * wrong stream position.
 */
class SprintResetParityTest {

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
    fun `kotlin sprint reset matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "sprint_reset_input.tsv"))
        val expected = rows(File(dir, "sprint_reset_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = SprintResetLogic.State()

        for ((i, row) in inputs.withIndex()) {
            var rolls = 0
            val d = SprintResetLogic.tick(
                state,
                SprintResetLogic.Input(
                    nowNs = row[1].toLong(),
                    hasTarget = row[2] == "1",
                    inRange = row[3] == "1",
                    gateOk = row[4] == "1",
                    isHurt = row[5] == "1",
                    entityId = row[6].toInt(),
                    chance = row[7].toInt(),
                    hitCount = row[8].toInt(),
                    delayMs = row[9].toInt(),
                    mode = row[10].toInt()
                )
            ) { rolls++; row[11].toInt() }

            val e = expected[i]
            assertEquals(e[1].toInt(), d.step.code, "step at tick $i")
            assertEquals(e[2] == "1", d.state.prevHurt, "prevHurt at tick $i")
            assertEquals(e[3].toInt(), d.state.lastTargetId, "lastTargetId at tick $i")
            assertEquals(e[4].toInt(), d.state.hitCounter, "hitCounter at tick $i")
            assertEquals(e[5] == "1", d.state.sending, "sending at tick $i")
            assertEquals(e[6].toLong(), d.state.sendAtNs, "sendAtNs at tick $i")
            assertEquals(e[7].toInt(), d.state.pendingMode, "pendingMode at tick $i")
            assertEquals(e[8].toInt(), rolls, "rolls consumed at tick $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every branch and the roll discipline`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "sprint_reset_input.tsv"))
        val expected = rows(File(dir, "sprint_reset_expected.tsv"))

        val steps = expected.groupingBy { it[1] }.eachCount()
        val noTarget = inputs.count { it[2] == "0" }
        val rangeFail = inputs.count { it[3] == "0" }
        val gateFail = inputs.count { it[4] == "0" }
        val sending = expected.count { it[5] == "1" }
        val rolled = expected.count { it[8] != "0" }

        assert((steps["0"] ?: 0) > 100) { "fixture rarely idles" }
        assert((steps["1"] ?: 0) > 10) { "fixture rarely sends Nostop" }
        assert((steps["2"] ?: 0) > 10) { "fixture rarely sends Silent" }
        assert(noTarget > 5) { "fixture rarely drops the target" }
        assert(rangeFail > 5) { "fixture rarely fails the range gate" }
        assert(gateFail > 5) { "fixture rarely fails the unified gate" }
        assert(sending > 5) { "fixture rarely arms a delayed send" }
        // Every tick that consumed a draw must be a chance < 100 input.
        inputs.indices.forEach { i ->
            if (expected[i][8] != "0") {
                assert(inputs[i][7].toInt() < 100) {
                    "a draw happened on tick $i with chance == 100 (no roll should occur)"
                }
            }
        }
        assert(rolled > 5) { "fixture rarely rolls the chance" }
    }
}
