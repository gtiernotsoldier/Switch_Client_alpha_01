package io.doppel.core.ipc

import io.doppel.core.strategy.player.JumpResetLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JumpReset parity: the Rust knock / pump machine against the Kotlin oracle.
 *
 * The fixture is a single 500-event replay, not independent cases: the two
 * halves share one state, and the interesting failures (a delayed jump armed
 * by a knock and fired one tick too early by a pump, a Ticks cooldown that
 * advances on a pump in ReceivedHits mode, a chance roll consumed on an idle
 * tick) only appear in sequence.
 *
 * The chance roll is scripted per row rather than drawn here: Pcg32 and
 * `kotlin.random` are different generators, so comparing them would test the
 * RNG, not the machine. The column that catches a porting mistake is `rolls` —
 * a port that draws on a pump or on a closed gate produces the same steps and
 * the wrong stream position.
 *
 * `kind` in the fixture selects the half: 0 = knock (velocity thread),
 * 1 = pump (client tick).
 */
class JumpResetParityTest {

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
    fun `kotlin jump reset matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "jump_reset_input.tsv"))
        val expected = rows(File(dir, "jump_reset_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = JumpResetLogic.State()

        for ((i, row) in inputs.withIndex()) {
            var rolls = 0
            val d = if (row[1] == "0") {
                JumpResetLogic.knock(
                    state,
                    JumpResetLogic.KnockInput(
                        gatesOpen = row[3] == "1",
                        nowNs = row[2].toLong(),
                        chance = row[4].toInt(),
                        cooldownMode = JumpResetLogic.CooldownMode.fromCode(row[5].toInt()),
                        ticksUntil = row[6].toInt(),
                        hitsUntil = row[7].toInt(),
                        delayMs = row[8].toInt()
                    )
                ) { rolls++; row[9].toInt() }
            } else {
                JumpResetLogic.pump(
                    state,
                    JumpResetLogic.PumpInput(
                        nowNs = row[2].toLong(),
                        cooldownMode = JumpResetLogic.CooldownMode.fromCode(row[5].toInt()),
                        ticksUntil = row[6].toInt()
                    )
                )
            }

            val e = expected[i]
            assertEquals(e[1].toInt(), d.step.code, "step at event $i")
            assertEquals(e[2].toInt(), d.state.cooldownCounter, "cooldown at event $i")
            assertEquals(e[3] == "1", d.state.pending, "pending at event $i")
            assertEquals(e[4].toLong(), d.state.deadlineNs, "deadline at event $i")
            assertEquals(e[5].toInt(), rolls, "rolls consumed at event $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises both halves, both cooldown modes and the roll discipline`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "jump_reset_input.tsv"))
        val expected = rows(File(dir, "jump_reset_expected.tsv"))

        val steps = expected.groupingBy { it[1] }.eachCount()
        val knocks = inputs.count { it[1] == "0" }
        val pumps = inputs.count { it[1] == "1" }
        val closedGates = inputs.count { it[3] == "0" }
        val ticksMode = inputs.count { it[5] == "0" }
        val receivedHitsMode = inputs.count { it[5] == "1" }
        val pending = expected.count { it[3] == "1" }
        val rolled = expected.count { it[5] != "0" }

        assert((steps["0"] ?: 0) > 100) { "fixture rarely idles" }
        assert((steps["1"] ?: 0) > 10) { "fixture rarely jumps" }
        assert(knocks > 50) { "fixture rarely sends a knockback" }
        assert(pumps > 50) { "fixture rarely pumps a tick" }
        assert(closedGates > 5) { "fixture rarely closes the gates" }
        assert(ticksMode > 5) { "fixture rarely uses the Ticks cooldown" }
        assert(receivedHitsMode > 5) { "fixture rarely uses the ReceivedHits cooldown" }
        assert(pending > 5) { "fixture rarely arms a delayed jump" }

        // Every draw must belong to a knock (never a pump) with an open gate
        // and chance < 100. A pump or a closed gate drawing would advance the
        // stream with frame timing, which is exactly the porting mistake this
        // replay is meant to catch.
        inputs.indices.forEach { i ->
            if (expected[i][5] != "0") {
                assert(inputs[i][1] == "0") { "a pump drew on event $i" }
                assert(inputs[i][3] == "1") { "a closed gate drew on event $i" }
                assert(inputs[i][4].toInt() < 100) {
                    "a draw happened on event $i with chance == 100 (no roll should occur)"
                }
            }
        }
        assert(rolled > 5) { "fixture rarely rolls the chance" }
    }
}
