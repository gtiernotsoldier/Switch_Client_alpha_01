package io.doppel.core.math

import io.doppel.contract.math.PortableMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [PortableMath] against the fdlibm reference, replayed from a checked-in fixture.
 *
 * ## Where the expected values come from
 *
 * Not from either implementation under test. `core-rs/golden/trig_expected.tsv`
 * was produced by compiling the **original C** (`__sin.c`, `__cos.c`,
 * `__rem_pio2.c`, `__rem_pio2_large.c`, `atan.c`, `atan2.c`, `acos.c` from
 * musl/FreeBSD `lib/msun`) with `-ffp-contract=off` and running 4000 inputs
 * through it. That file is the external truth; both [PortableMath] and
 * `core-rs/src/trig.rs` are held to it, so neither can drift into agreeing with
 * the other's bug.
 *
 * The Rust transcription was additionally checked against that C build on
 * 200 000 inputs x 5 functions with zero differing bits before this fixture was
 * cut down to a size worth committing.
 *
 * ## Why bits, not decimals
 *
 * Everything is compared as raw `Double.doubleToRawLongBits`. Printing these
 * values and parsing them back is exact for double — but a test that compares
 * `"0.5403...".toDouble()` reads as if it tolerates rounding, and the whole
 * point is that it does not. Bits also make a failure say exactly which bit.
 */
class PortableMathParityTest {

    private fun goldenDir(): File {
        val candidates = listOf(
            File("../core-rs/golden"),
            File("Doppel/core-rs/golden"),
            File("core-rs/golden")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("golden fixtures not found. Run: cd Doppel/core-rs && cargo run -- --emit-golden golden")
    }

    /**
     * The fixture stores raw bit patterns as unsigned decimal, because that is
     * what C's `%llu` produces. Half of them exceed [Long.MAX_VALUE] — a `toLong`
     * here throws, which is how the signed/unsigned mix-up first showed up.
     */
    private fun rows(f: File): List<List<Long>> =
        f.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                line.trim().split(Regex("\\s+")).map { java.lang.Long.parseUnsignedLong(it) }
            }

    @Test
    fun matchesFdlibmReferenceBitForBit() {
        val dir = goldenDir()
        val input = rows(File(dir, "trig_input.tsv"))
        val expected = rows(File(dir, "trig_expected.tsv"))
        assertEquals(input.size, expected.size, "fixture rows disagree")

        var checked = 0
        for (i in input.indices) {
            val (xb, yb, ab) = Triple(input[i][0], input[i][1], input[i][2])
            val x = java.lang.Double.longBitsToDouble(xb)
            val y = java.lang.Double.longBitsToDouble(yb)
            val a = java.lang.Double.longBitsToDouble(ab)

            assertBits(expected[i][0], PortableMath.sin(x), "row $i sin($x)")
            assertBits(expected[i][1], PortableMath.cos(x), "row $i cos($x)")
            assertBits(expected[i][2], PortableMath.atan(y), "row $i atan($y)")
            assertBits(expected[i][3], PortableMath.atan2(y, x), "row $i atan2($y, $x)")
            assertBits(expected[i][4], PortableMath.acos(a), "row $i acos($a)")
            checked++
        }
        assertEquals(4000, checked, "fixture row count changed")
    }

    private fun assertBits(want: Long, got: Double, what: String) {
        val g = java.lang.Double.doubleToRawLongBits(got)
        assertEquals(
            java.lang.String.format("%016x", want),
            java.lang.String.format("%016x", g),
            "$what — one bit off is still a different decision downstream"
        )
    }

    /**
     * Proof that this class is an independent implementation, not a forwarder.
     *
     * The claim in the KDoc is that the platform maths cannot be trusted for
     * bit-exact parity. `StrictMath` is itself fdlibm, so it agrees with this
     * file on almost everything — the disagreement is sparse and specific, which
     * is exactly why it is dangerous: it survives every casual test and then
     * flips a comparison somewhere in a thousand hours of play.
     *
     * So the assertion is deliberately weak: *at least one* difference over
     * 20000 points. A weak assertion is the right shape here — it only has to
     * fail if someone replaces the body with `StrictMath::sin`, and it stays
     * green across JDK builds that shift which individual points differ.
     */
    @Test
    fun isNotSimplyForwardingToPlatformMaths() {
        var diff = 0
        var firstAt = Double.NaN
        for (k in 0 until 20000) {
            val x = 0.1234567 + k * 0.0001
            val sinDiff = java.lang.Double.doubleToRawLongBits(PortableMath.sin(x)) !=
                java.lang.Double.doubleToRawLongBits(StrictMath.sin(x))
            val cosDiff = java.lang.Double.doubleToRawLongBits(PortableMath.cos(x)) !=
                java.lang.Double.doubleToRawLongBits(StrictMath.cos(x))
            if (sinDiff || cosDiff) {
                diff++
                if (firstAt.isNaN()) firstAt = x
            }
        }
        assert(diff > 0) {
            "PortableMath agrees with StrictMath on all 20000 points — " +
                "it is no longer an independent implementation"
        }
        println("PortableMath vs StrictMath on 20000 points: $diff differ, first at x=$firstAt")
    }
}
