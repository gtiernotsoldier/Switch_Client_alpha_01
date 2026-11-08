package io.doppel.core.ipc

import io.doppel.core.strategy.player.LagRangeLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * LagRange parity: the Rust hold machine against the Kotlin oracle.
 *
 * The fixture is a single 500-tick replay, not independent cases: which
 * edge fires on the tick after a flush depends on the state the flush left
 * behind, so the interesting failures (a damage edge detected one tick
 * late, a sprint edge that survived an option toggle, a hold that outlived
 * its deadline) only appear in sequence.
 *
 * There is no randomness, so there is no roll column to script.
 * `last_dist` is compared as **raw f32 bits**: a parity check that compares
 * printed decimals can pass while the last mantissa bit differs, which is
 * exactly the drift a replay is supposed to catch (the velocity fixture
 * established the same rule for f64).
 */
class LagRangeParityTest {

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
    fun `kotlin lag range matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "lag_range_input.tsv"))
        val expected = rows(File(dir, "lag_range_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = LagRangeLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val d = LagRangeLogic.tick(
                state,
                LagRangeLogic.Input(
                    nowNs = row[1].toLong(),
                    guiOpen = row[2] == "1",
                    hasDist = row[3] == "1",
                    dist = row[4].toDouble(),
                    range = row[5].toDouble(),
                    maxDelayMs = row[6].toInt(),
                    sprintResetOn = row[7] == "1",
                    isSprinting = row[8] == "1",
                    blockSwordOn = row[9] == "1",
                    isBlocking = row[10] == "1",
                    holdingWeaponOn = row[11] == "1",
                    weaponIsSword = row[12] == "1",
                    hurtTime = row[13].toInt()
                )
            )

            val e = expected[i]
            assertEquals(e[1].toInt(), d.code, "step at tick $i")
            assertEquals(
                java.lang.Integer.parseUnsignedInt(e[2], 16),
                java.lang.Float.floatToRawIntBits(d.state.lastDist),
                "last distance bits at tick $i"
            )
            assertEquals(e[3].toInt(), d.state.lastSelfHurt, "hurt tracker at tick $i")
            assertEquals(e[4] == "1", d.state.lastSprint, "sprint edge tracker at tick $i")
            assertEquals(e[5] == "1", d.state.lastBlocking, "block edge tracker at tick $i")
            assertEquals(e[6].toLong(), d.state.holdStartNs, "hold start at tick $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every step and every flush reason`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "lag_range_input.tsv"))
        val expected = rows(File(dir, "lag_range_expected.tsv"))

        val steps = expected.groupingBy { it[1] }.eachCount()
        assert((steps["2"] ?: 0) > 20) { "fixture rarely arms a hold" }
        assert((steps["1"] ?: 0) > 5) { "fixture rarely extends a hold" }
        assert((steps["3"] ?: 0) > 50) { "fixture rarely flushes" }

        // Every flush reason must appear at least once: a port that drops a
        // branch flushes less often and shows up here. Each classification
        // replays the machine's own branch order, using the *previous*
        // expected row's state (the edge trackers and the distance base are
        // what the tick actually compared against).
        var gateFlushes = 0       // gui / no-dist / out-of-range
        var damageFlushes = 0
        var sprintEdgeFlushes = 0
        var blockEdgeFlushes = 0
        var weaponFlushes = 0
        var notClosingFlushes = 0
        var deadlineFlushes = 0
        for (i in inputs.indices) {
            if (expected[i][1] != "3") continue
            val row = inputs[i]
            val gate = row[2] == "1" || row[3] == "0" ||
                row[4].toDouble().toFloat() > row[5].toDouble().toFloat()
            if (gate) {
                gateFlushes++
                continue
            }
            val prevHurt = if (i > 0) expected[i - 1][3].toInt() else 0
            if (row[13].toInt() > prevHurt) {
                damageFlushes++
                continue
            }
            val prevSprint = i > 0 && expected[i - 1][4] == "1"
            if (row[7] == "1" && row[8] == "1" && !prevSprint) {
                sprintEdgeFlushes++
                continue
            }
            val prevBlocking = i > 0 && expected[i - 1][5] == "1"
            if (row[9] == "1" && row[10] == "1" && !prevBlocking) {
                blockEdgeFlushes++
                continue
            }
            if (row[11] == "1" && row[12] == "0") {
                weaponFlushes++
                continue
            }
            // Past every edge: the flush came from the closing comparison or
            // the maxDelay deadline, told apart by whether the distance was
            // still shrinking against the previous expected base.
            val prevDist = if (i > 0) Float.fromBits(
                java.lang.Integer.parseUnsignedInt(expected[i - 1][2], 16)
            ) else -1f
            if (row[4].toDouble().toFloat() >= prevDist) {
                notClosingFlushes++
            } else {
                deadlineFlushes++
            }
        }
        assert(gateFlushes > 0) { "fixture never flushes on the gate" }
        assert(damageFlushes > 0) { "fixture never flushes on damage" }
        assert(sprintEdgeFlushes > 0) { "fixture never flushes on a sprint edge" }
        assert(blockEdgeFlushes > 0) { "fixture never flushes on a block edge" }
        assert(weaponFlushes > 0) { "fixture never flushes on a dropped sword" }
        assert(notClosingFlushes > 0) { "fixture never flushes on a target that stopped closing in" }
        assert(deadlineFlushes > 0) { "fixture never flushes on the maxDelay deadline" }
    }
}
