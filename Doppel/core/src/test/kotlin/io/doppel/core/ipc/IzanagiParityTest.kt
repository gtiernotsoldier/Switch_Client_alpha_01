package io.doppel.core.ipc

import io.doppel.core.strategy.player.IzanagiLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Izanagi parity: the Rust Mark & Recall machine against the Kotlin oracle.
 *
 * The fixture is a single 500-tick replay, not independent cases: which edges
 * are refused, where a walk lands after a stall, and how an anchor ages out of
 * the ring only appear in sequence. Position columns are compared as **raw f64
 * bits** — a printed-decimal comparison can pass while the last mantissa bit
 * differs, which is exactly the drift a replay exists to catch (the velocity
 * fixture established the same rule).
 *
 * `kamui_busy` is a scripted column in the fixture (it drives the machine
 * directly); in production the session derives it from the sibling state.
 */
class IzanagiParityTest {

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
    fun `kotlin izanagi matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "izanagi_input.tsv"))
        val expected = rows(File(dir, "izanagi_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = IzanagiLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val d = IzanagiLogic.tick(
                state,
                IzanagiLogic.Input(
                    feed = row[1] == "1",
                    tickClock = row[2].toInt(),
                    x = row[3].toDouble(),
                    y = 64.0,
                    z = row[4].toDouble(),
                    onGround = row[5] == "1",
                    markEdge = row[6] == "1",
                    recallEdge = row[7] == "1",
                    corridorOk = row[8] == "1",
                    guiOpen = row[9] == "1",
                    dead = row[10] == "1",
                    kamuiBusy = row[11] == "1",
                    maxDepth = row[12].toInt(),
                    stallCap = row[13].toInt(),
                    cooldownTicks = row[14].toInt()
                )
            )

            val e = expected[i]
            assertEquals(e[1].toInt(), d.answer.state, "machine state at tick $i")
            assertEquals(e[2].toInt(), d.answer.flags, "flags at tick $i")
            assertEquals(e[3].toInt(), d.answer.remaining, "remaining at tick $i")
            assertEquals(
                java.lang.Long.parseUnsignedLong(e[4], 16),
                java.lang.Double.doubleToRawLongBits(d.answer.x),
                "x bits at tick $i"
            )
            assertEquals(
                java.lang.Long.parseUnsignedLong(e[5], 16),
                java.lang.Double.doubleToRawLongBits(d.answer.z),
                "z bits at tick $i"
            )

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every state and every exit path`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "izanagi_input.tsv"))
        val expected = rows(File(dir, "izanagi_expected.tsv"))

        var marks = 0
        var started = 0
        var advances = 0
        var completed = 0
        var abortedByStall = 0
        var abortedByGuiOrDeath = 0
        var refusals = 0
        var doneStates = 0

        for ((i, e) in expected.withIndex()) {
            val row = inputs[i]
            val state = e[1].toInt()
            val flags = e[2].toInt()

            if (row[6] == "1" && state == IzanagiLogic.STATE_MARKED) marks++
            if (flags and IzanagiLogic.FLAG_STARTED != 0) started++
            if (row[7] == "1" && (flags and IzanagiLogic.FLAG_STARTED) == 0) refusals++
            if (flags and IzanagiLogic.FLAG_ADVANCE != 0) advances++
            if (flags and IzanagiLogic.FLAG_DONE != 0) completed++
            if (flags and IzanagiLogic.FLAG_ABORTED != 0) {
                if (row[9] == "1" || row[10] == "1") abortedByGuiOrDeath++ else abortedByStall++
            }
            if (state == IzanagiLogic.STATE_COOLDOWN) doneStates++
        }

        // A port that drops a branch shows up as a missing bucket here.
        assertTrue(marks > 5) { "fixture rarely marks" }
        assertTrue(started > 5) { "fixture rarely starts a replay" }
        assertTrue(advances > 10) { "fixture rarely advances the walk" }
        assertTrue(completed > 2) { "fixture never completes a recall" }
        assertTrue(abortedByStall > 0) { "fixture never aborts on corridor stalls" }
        assertTrue(abortedByGuiOrDeath > 0) { "fixture never exits a replay via GUI/death" }
        assertTrue(refusals > 0) { "fixture never refuses a mark or recall" }
        assertTrue(doneStates > 10) { "fixture never reaches cooldown" }
    }
}
