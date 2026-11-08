package io.doppel.core.ipc

import io.doppel.core.strategy.player.SuperKnockbackLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * SuperKnockback parity: the Rust phase machine against the Kotlin oracle.
 *
 * The fixture is a single 500-tick replay, not independent cases: the machine
 * is stateful and the interesting failures (a delayed fire one tick early, a
 * coordination offset that fires twice, a SprintTap sequence re-armed by a
 * fresh hit, a chance roll consumed on a throttled hit) only appear in
 * sequence.
 *
 * The chance roll is scripted per row rather than drawn here: Pcg32 and
 * `kotlin.random` are different generators, so comparing them would test the
 * RNG, not the machine. The column that catches a porting mistake is `rolls`.
 *
 * `code` is packed: low nibble = the SprintTap step at the top of the tick,
 * high nibble = the fire step from the trigger evaluation. Both are compared
 * as one integer so a port that drops either write is caught.
 */
class SuperKnockbackParityTest {

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
    fun `kotlin super knockback matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "super_knockback_input.tsv"))
        val expected = rows(File(dir, "super_knockback_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = SuperKnockbackLogic.State()

        for ((i, row) in inputs.withIndex()) {
            var rolls = 0
            val d = SuperKnockbackLogic.tick(
                state,
                SuperKnockbackLogic.Input(
                    nowNs = row[1].toLong(),
                    hasTarget = row[2] == "1",
                    triggerOk = row[3] == "1",
                    inRange = row[4] == "1",
                    gateOk = row[5] == "1",
                    entityId = row[6].toInt(),
                    chance = row[7].toInt(),
                    hitCount = row[8].toInt(),
                    delayMs = row[9].toInt(),
                    mode = row[10].toInt(),
                    sprintTapAllowed = row[11] == "1",
                    coordinationActive = row[12] == "1"
                )
            ) { rolls++; row[13].toInt() }

            val e = expected[i]
            assertEquals(e[1].toInt(), d.code, "packed step at tick $i")
            assertEquals(e[2].toInt(), d.state.phase.code, "phase at tick $i")
            assertEquals(e[3].toLong(), d.state.delayEndNs, "deadline at tick $i")
            assertEquals(e[4].toInt(), d.state.coordOffsetTicks, "coordination offset at tick $i")
            assertEquals(e[5].toInt(), d.state.sprintTicks, "sprint ticks at tick $i")
            assertEquals(e[6].toInt(), d.state.hitCounter, "hit counter at tick $i")
            assertEquals(e[7].toInt(), d.state.lastTargetId, "last target at tick $i")
            assertEquals(e[8].toInt(), rolls, "rolls consumed at tick $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every phase, every mode and the roll discipline`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "super_knockback_input.tsv"))
        val expected = rows(File(dir, "super_knockback_expected.tsv"))

        val fireSteps = expected.groupingBy { (it[1].toInt() shr 4) and 0xF }.eachCount()
        val sprintSteps = expected.groupingBy { it[1].toInt() and 0xF }.eachCount()
        val phases = expected.groupingBy { it[2] }.eachCount()
        val rolled = expected.count { it[8] != "0" }
        val packedBoth = expected.count {
            (it[1].toInt() and 0xF) != 0 && ((it[1].toInt() shr 4) and 0xF) != 0
        }

        assert((fireSteps[4] ?: 0) > 5) { "fixture rarely fires SprintTap" }
        assert((fireSteps[5] ?: 0) > 5) { "fixture rarely fires Old" }
        assert((fireSteps[6] ?: 0) > 5) { "fixture rarely fires SneakPacket" }
        assert((phases["1"] ?: 0) > 5) { "fixture rarely arms a delayed fire" }
        assert((phases["2"] ?: 0) > 5) { "fixture rarely runs the SprintTap sequence" }
        assert((sprintSteps[1] ?: 0) > 3) { "fixture rarely breaks sprint" }
        assert((sprintSteps[2] ?: 0) > 3) { "fixture rarely restores sprint" }
        assert((sprintSteps[3] ?: 0) > 3) { "fixture rarely releases the claim" }
        assert(packedBoth > 0) { "fixture never needs two platform writes in one tick" }

        // A draw may only happen at a fire point: target present, trigger met,
        // range and gate open, chance < 100. A draw on an idle / throttled /
        // delayed / coordinating tick would advance the stream and diverge.
        inputs.indices.forEach { i ->
            if (expected[i][8] != "0") {
                assert(inputs[i][2] == "1") { "a draw without a target at tick $i" }
                assert(inputs[i][3] == "1") { "a draw without a trigger at tick $i" }
                assert(inputs[i][4] == "1") { "a draw with the range gate closed at tick $i" }
                assert(inputs[i][5] == "1") { "a draw with the unified gate closed at tick $i" }
                assert(inputs[i][7].toInt() < 100) {
                    "a draw happened at tick $i with chance == 100 (no roll should occur)"
                }
            }
        }
        assert(rolled > 5) { "fixture rarely rolls the chance" }
    }
}
