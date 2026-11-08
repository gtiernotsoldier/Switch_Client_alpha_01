package io.doppel.core.ipc

import io.doppel.core.strategy.player.HitSelectLogic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * HitSelect parity: the two Rust ops against the Kotlin oracle, over one shared
 * state.
 *
 * The fixture interleaves ticks and click gates. Replaying them separately
 * would hide the bug this module is most likely to have: a gate reading a
 * window that the tick half never opened into the same state.
 */
class HitSelectParityTest {

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

    private fun flag(v: String): Boolean = v.toInt() != 0

    @Test
    fun `kotlin hit select matches the rust port over the interleaved stream`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "hit_select_input.tsv"))
        val expected = rows(File(dir, "hit_select_expected.tsv"))
        assertEquals(inputs.size, expected.size, "input and expected rows must line up")

        val state = HitSelectLogic.State()

        for ((i, r) in inputs.withIndex()) {
            val result = if (r[1].toInt() == 0) {
                HitSelectLogic.tick(
                    state,
                    HitSelectLogic.TickInput(
                        nowNs = r[2].toLong(),
                        canCrit = flag(r[3]),
                        playerHurtTime = r[4].toInt(),
                        leftMouseDown = flag(r[5]),
                        hasTarget = flag(r[6]),
                        targetId = r[7].toInt(),
                        targetHurtTime = r[8].toInt(),
                        counterHit = flag(r[9]),
                        counterCdMs = r[10].toInt()
                    )
                )
            } else {
                val rolls = listOf(r[21].toInt(), r[22].toInt())
                var calls = 0
                HitSelectLogic.gate(
                    state,
                    HitSelectLogic.GateInput(
                        nowNs = r[2].toLong(),
                        guiOpen = flag(r[11]),
                        lookingAtBlock = flag(r[12]),
                        chance = r[13].toInt(),
                        criticals = flag(r[14]),
                        missedSwing = r[15].toInt(),
                        hasForwardTarget = flag(r[16]),
                        forwardHurtResistantTime = r[17].toInt(),
                        waitFirstMs = r[18].toInt(),
                        retimeAt = r[19].toInt(),
                        pauseMs = r[20].toInt()
                    )
                ) {
                    val n = calls
                    calls++
                    rolls[minOf(n, 1)]
                }
            }
            assertEquals(expected[i][1].toInt() != 0, result, "result at row $i (kind=${r[1]})")
        }
    }

    @Test
    fun `the fixture exercises both halves and both answers`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "hit_select_input.tsv"))
        val results = rows(File(dir, "hit_select_expected.tsv"))

        val ticks = inputs.indices.filter { inputs[it][1].toInt() == 0 }
        val gates = inputs.indices.filter { inputs[it][1].toInt() == 1 }
        assert(ticks.size > 100) { "only ${ticks.size} ticks" }
        assert(gates.size > 100) { "only ${gates.size} gates" }

        val fired = ticks.count { results[it][1].toInt() != 0 }
        val passed = gates.count { results[it][1].toInt() != 0 }
        assert(fired > 5) { "CounterHit only fired $fired times" }
        assert(passed > 50) { "gate only passed $passed times" }
        assert(gates.size - passed > 30) { "gate only held ${gates.size - passed} times" }
    }
}
