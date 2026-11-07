package io.doppel.core.ipc

import io.doppel.core.strategy.autoblock.RangeGate
import io.doppel.core.strategy.movement.SocdResolver
import io.doppel.core.strategy.movement.StopTap
import io.doppel.core.strategy.movement.StrafeCorrector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Movement-family parity: SOCD, instant stop, strafe corrector, range gate.
 *
 * All four are deterministic, so this runs without an RNG stream and any red
 * line is arithmetic. The strafe corrector is the one to watch: it calls
 * `hypot` and `Math.toRadians`, and both have a choice of implementation that
 * differs by an ulp — the fixture records raw f64 bits precisely so that an ulp
 * difference is a failure rather than a rounding note.
 */
class MovementParityTest {

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
    fun `kotlin movement matches the rust port`() {
        val dir = goldenDir()
        val inputs = rows(File(dir, "movement_input.tsv"))
        val expected = rows(File(dir, "movement_expected.tsv"))
        assertEquals(inputs.size * 4, expected.size)

        val socd = SocdResolver()
        val gateState = RangeGate.State()
        var checked = 0

        for ((rowIndex, row) in inputs.withIndex()) {
            val idx = row[0].toInt()
            val fwd = row[1].toInt() == 1
            val back = row[2].toInt() == 1
            val left = row[3].toInt() == 1
            val right = row[4].toInt() == 1
            val yaw = row[5].toFloat()
            val mx = row[6].toDouble()
            val mz = row[7].toDouble()
            val dist = row[8].toFloat()
            val bearing = row[9].toDouble()
            val reach = row[10].toFloat()

            // 0 — SOCD
            val c = socd.resolve(fwd, back, left, right)
            val mask = (if (c.suppressForward) 1 else 0) or
                (if (c.suppressBack) 2 else 0) or
                (if (c.suppressLeft) 4 else 0) or
                (if (c.suppressRight) 8 else 0)
            val e0 = expected[rowIndex * 4]
            assertEquals(idx, e0[0].toInt())
            assertEquals(e0[2].toInt(), mask, "tick $idx socd")

            // 1 — instant stop
            val sliding = StopTap.stillSliding(mx, mz, yaw, true, StopTap.MIN_SPEED)
            assertEquals(expected[rowIndex * 4 + 1][2].toInt(), if (sliding) 1 else 0, "tick $idx stop_tap")

            // 2 — strafe corrector
            val si = StrafeCorrector.Input(forward = fwd, back = back, left = left, right = right)
            val ss = StrafeCorrector.State(
                distance = dist.toDouble(),
                bearingRad = bearing,
                yawDeg = yaw.toDouble(),
                reach = reach.toDouble()
            )
            val sc = StrafeCorrector.correct(si, ss)
            val smask = (if (sc.suppressForward) 1 else 0) or
                (if (sc.suppressBack) 2 else 0) or
                (if (sc.suppressLeft) 4 else 0) or
                (if (sc.suppressRight) 8 else 0)
            val e2 = expected[rowIndex * 4 + 2]
            assertEquals(e2[2].toInt(), smask, "tick $idx strafe mask")
            assertEquals(
                e2[3].toULong(16).toLong(),
                java.lang.Double.doubleToRawLongBits(sc.before),
                "tick $idx strafe before"
            )
            assertEquals(
                e2[4].toULong(16).toLong(),
                java.lang.Double.doubleToRawLongBits(sc.after),
                "tick $idx strafe after"
            )

            // 3 — range gate
            val cfg = RangeGate.Config(
                mode = RangeGate.Mode.ON_ENTER,
                minDistance = 0f,
                maxDistance = 4f,
                hysteresis = 1.5f
            )
            val inRange = RangeGate.compute(gateState, cfg, dist)
            assertEquals(expected[rowIndex * 4 + 3][2].toInt(), if (inRange) 1 else 0, "tick $idx range_gate")

            checked += 4
        }

        assertEquals(inputs.size * 4, checked)
        println("movement parity: $checked decisions identical (${inputs.size} ticks x 4 modules)")
    }
}
