package io.doppel.core.ipc

import io.doppel.core.strategy.player.KamuiLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Kamui parity: the Rust phase machine against the Kotlin oracle.
 *
 * The fixture is a single 500-tick replay, not independent cases: which edge
 * is refused, whether a cast runs under the knobs it latched at its trigger,
 * and where a phase lands after a cooldown ends only appear in sequence. The
 * expected columns are the MACHINE (state, flags, phase tick, cooldown tick,
 * casts) — the packing into one scalar is unit-tested in Rust and round-tripped
 * on the wire, while this fixture pins the machine.
 *
 * There is no randomness, so there is no roll column to script.
 */
class KamuiParityTest {

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
    fun `kotlin kamui matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "kamui_input.tsv"))
        val expected = rows(File(dir, "kamui_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        var state = KamuiLogic.State()

        for ((i, row) in inputs.withIndex()) {
            val d = KamuiLogic.tick(
                state,
                KamuiLogic.Input(
                    triggerEdge = row[1] == "1",
                    guiOpen = row[2] == "1",
                    dead = row[3] == "1",
                    phaseTicks = row[4].toInt(),
                    maxHoldTicks = row[5].toInt(),
                    cooldownTicks = row[6].toInt()
                )
            )

            val e = expected[i]
            assertEquals(e[1].toInt(), d.answer.state, "machine state at tick $i")
            assertEquals(e[2].toInt(), (if (d.answer.claim) KamuiLogic.FLAG_CLAIM else 0) or
                (if (d.answer.release) KamuiLogic.FLAG_RELEASE else 0), "flags at tick $i")
            assertEquals(e[3].toInt(), d.answer.phaseTick, "phase tick at tick $i")
            assertEquals(e[4].toInt(), d.answer.cooldownTick, "cooldown tick at tick $i")
            assertEquals(e[5].toInt(), d.state.casts, "cast counter at tick $i")

            state = d.state
        }
    }

    @Test
    fun `the fixture exercises every state, every release reason and every refusal path`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "kamui_input.tsv"))
        val expected = rows(File(dir, "kamui_expected.tsv"))

        var starts = 0
        var claims = 0
        var capReleases = 0
        var guiReleases = 0
        var deathReleases = 0
        var refusedInLivePhase = 0
        var refusedWithNoPhase = 0
        var cooldownEnds = 0
        var midFlightRetunes = 0
        var prevState = 0

        for ((i, e) in expected.withIndex()) {
            val row = inputs[i]
            val edge = row[1] == "1"
            val gui = row[2] == "1"
            val dead = row[3] == "1"
            val state = e[1].toInt()
            val flags = e[2].toInt()
            val phaseTick = e[3].toInt()

            // The trigger-tick signature: state PHASE, phaseTick 0, no flags —
            // the tick that consumed the edge only started the phase.
            if (state == KamuiLogic.STATE_PHASE && phaseTick == 0 && flags == 0) {
                starts++
                // A GUI or death scheduled onto the NEXT few ticks must land
                // while the phase is live for the override paths to fire; the
                // knobs may also be retuned mid-flight (the machine must ignore
                // that — the cast runs on the set it latched).
                if (i + 1 < inputs.size && inputs[i + 1][4] != row[4]) midFlightRetunes++
            }
            if ((flags and KamuiLogic.FLAG_CLAIM) != 0) claims++
            if ((flags and KamuiLogic.FLAG_RELEASE) != 0) {
                when {
                    dead -> deathReleases++
                    gui -> guiReleases++
                    else -> capReleases++
                }
            }
            if (state == KamuiLogic.STATE_PHASE && edge && phaseTick > 0) refusedInLivePhase++
            if ((state == KamuiLogic.STATE_IDLE || state == KamuiLogic.STATE_COOLDOWN) && edge) {
                refusedWithNoPhase++
            }
            if (state == KamuiLogic.STATE_IDLE && prevState == KamuiLogic.STATE_COOLDOWN) cooldownEnds++
            prevState = state
        }

        // A port that drops a branch shows up as a missing bucket here.
        assertTrue(starts > 10) { "fixture rarely starts a phase" }
        assertTrue(claims > 20) { "fixture rarely refreshes a claim" }
        assertTrue(capReleases > 0) { "fixture never releases at the cap" }
        assertTrue(guiReleases > 0) { "fixture never releases on a mid-phase GUI" }
        assertTrue(deathReleases > 0) { "fixture never releases on a mid-phase death" }
        assertTrue(refusedInLivePhase > 0) { "fixture never refuses an edge during a live phase" }
        assertTrue(refusedWithNoPhase > 0) { "fixture never refuses an edge outside a phase" }
        assertTrue(cooldownEnds > 0) { "fixture never finishes a cooldown" }
        assertTrue(midFlightRetunes > 0) { "fixture never retunes the knobs mid-flight" }
    }
}
