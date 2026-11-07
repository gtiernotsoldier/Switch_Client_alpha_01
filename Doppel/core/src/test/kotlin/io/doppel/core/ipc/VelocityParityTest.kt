package io.doppel.core.ipc

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.core.strategy.velocity.LegitVelocityStrategy
import io.doppel.contract.strategy.velocity.VelocityConfig
import io.doppel.contract.strategy.velocity.VelocityResult
import io.doppel.contract.strategy.velocity.VelocityStrategy
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import io.doppel.core.rng.DecisionRng

/**
 * The switch criterion from PROTECTION.md 9.5.5, made executable.
 *
 * `core-rs --emit-golden` writes 2000 snapshots and the decisions the Rust port
 * produced for them. This test replays the same inputs through the Kotlin
 * strategy with the same seed and asserts the decisions match **bit for bit** —
 * action, reason, and the raw f64 bits of the reduced motion.
 *
 * Why this is not circular: the Kotlin side is the reference implementation
 * today. The fixture was generated from the Rust port, so a match proves the port
 * agrees with the code that is currently shipping. A behaviour change on either
 * side breaks it until the fixture is deliberately regenerated — which makes the
 * change visible in review instead of silent.
 *
 * If this test is red, the migration is not done. That is the whole point of it.
 */
class VelocityParityTest {

    private fun goldenDir(): File {
        val candidates = listOf(
            File("../core-rs/golden"),
            File("Doppel/core-rs/golden"),
            File("core-rs/golden")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "golden fixtures not found. Run: cd Doppel/core-rs && cargo run -- --emit-golden golden"
            )
    }

    /** Must match `selftest_ctx().player` in `core-rs/src/main.rs` exactly. */
    private fun player() = PlayerState(
        name = "",
        position = Vec3(0.0, 64.0, 0.0),
        rotation = Vec2(0f, 0f),
        motionX = 0.1,
        motionY = 0.0,
        motionZ = 0.1,
        onGround = true,
        isMoving = true,
        isMovingForward = true,
        isSprinting = false,
        health = 20f,
        hurtTime = 0,
        maxHurtResistantTime = 10,
        hurtResistantTime = 0,
        isBlocking = false,
        isUsingItem = false,
        isLookingAtTarget = false,
        isMining = false,
        isSneaking = false,
        selectedSlot = 0,
        weaponType = WeaponType.SWORD,
        isAttackKeyDown = false,
        ticks = 0L
    )

    private fun actionCode(r: VelocityResult): Int = when (r) {
        is VelocityResult.Modify -> 2
        is VelocityResult.Pass -> 1
        is VelocityResult.ClickBurst -> 3
        is VelocityResult.Cancel -> 4
        is VelocityResult.NoOp -> 0
    }

    private fun motionOf(r: VelocityResult): Vec3? = when (r) {
        is VelocityResult.Modify -> r.motion
        is VelocityResult.Pass -> r.originalMotion
        else -> null
    }

    @Test
    fun `kotlin and rust agree on 2000 ticks`() {
        val dir = goldenDir()
        val inputLines = dir.resolve("velocity_input.tsv").readLines()
        val expectedLines = dir.resolve("velocity_expected.tsv").readLines()

        val inputs = inputLines.filter { it.isNotBlank() && !it.startsWith("#") }
        val expected = expectedLines.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(inputs.size - 1, expected.size, "fixture row counts disagree")

        val seed = 0x0123456789ABCDEFL
        val rng = DecisionRng(seed)
        val strategy = LegitVelocityStrategy(rng)
        val state = VelocityStrategy.State()
        val config = VelocityConfig(reduceGivenReduced = 70, reduceGivenSkipped = 30)
        val player = player()

        var checked = 0
        var modifies = 0

        for (i in 1 until inputs.size) {
            val cols = inputs[i].split("\t")
            val idx = cols[0].toInt()
            val ctx = VelocityContext(
                originalMotion = Vec3(cols[1].toDouble(), cols[2].toDouble(), cols[3].toDouble()),
                player = player,
                target = null,
                packetHandle = Unit,
                isKnockbackHit = true
            )

            val result = strategy.execute(config, state, ctx)

            val want = expected[i - 1].split("\t")
            assertEquals(idx, want[0].toInt(), "row order drifted at index $i")

            val wantAction = want[1].toInt()
            assertEquals(
                wantAction, actionCode(result),
                "action diverged at tick $idx (rust=$wantAction kotlin=${actionCode(result)})"
            )

            if (wantAction == 2) {
                modifies++
                val motion = motionOf(result)!!
                assertEquals(
                    java.lang.Long.parseUnsignedLong(want[3], 16),
                    java.lang.Double.doubleToRawLongBits(motion.x),
                    "motion.x bits diverged at tick $idx"
                )
                assertEquals(
                    java.lang.Long.parseUnsignedLong(want[4], 16),
                    java.lang.Double.doubleToRawLongBits(motion.y),
                    "motion.y bits diverged at tick $idx"
                )
                assertEquals(
                    java.lang.Long.parseUnsignedLong(want[5], 16),
                    java.lang.Double.doubleToRawLongBits(motion.z),
                    "motion.z bits diverged at tick $idx"
                )
            }
            checked++
        }

        assertEquals(2000, checked)
        assertTrue(modifies > 500, "fixture should exercise the reduce path, saw $modifies")
    }
}
