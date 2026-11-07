package io.doppel.core.ipc

import io.doppel.core.model.Hitbox
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.aim.AimConfig
import io.doppel.core.strategy.aim.AimInput
import io.doppel.core.strategy.aim.AimResult
import io.doppel.core.strategy.aim.AimStrategy
import io.doppel.core.strategy.aim.LegitAimStrategy
import io.doppel.core.strategy.aim.SelfAdaptiveAimStrategy
import io.doppel.core.strategy.click.WeaponType
import io.doppel.core.util.Vec2
import io.doppel.core.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Aim parity against the Rust port, replaying `core-rs --emit-golden` fixtures.
 *
 * Same contract as [VelocityParityTest]: the fixture holds 1000 synthetic
 * snapshots and the decisions the Rust implementation produced for them, and
 * this test asserts the Kotlin reference produces the same decisions to the bit.
 *
 * Two independent RNG streams, one per mode, matching `emit_aim_golden`. Any
 * extra or missing draw inside a branch desynchronises the stream and fails the
 * test on that tick — which is the only way to catch a branch that looks right
 * but consumes randomness differently.
 */
class AimParityTest {

    private fun goldenDir(): File {
        val candidates = listOf(
            File("../core-rs/golden"),
            File("Doppel/core-rs/golden"),
            File("core-rs/golden")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("golden fixtures not found. Run: cd Doppel/core-rs && cargo run -- --emit-golden golden")
    }

    private fun readRows(f: File): List<Array<String>> =
        f.readLines()
            // The column-header line carries no `#`, so filter on content rather
            // than prefix: every data row starts with the tick index.
            .filter { it.isNotBlank() && !it.startsWith("#") && it[0].isDigit() }
            .map { it.split("\t").toTypedArray() }

    private fun seedOf(f: File): Long =
        f.readLines().first { it.startsWith("# seed ") }.removePrefix("# seed ").trim().toLong()

    private fun player(
        px: Double, py: Double, pz: Double,
        yaw: Float, pitch: Float,
        flags: Int, hurt: Int, tick: Long
    ): PlayerState = PlayerState(
        name = "",
        position = Vec3(px, py, pz),
        rotation = Vec2(yaw, pitch),
        motionX = 0.0,
        motionY = 0.0,
        motionZ = 0.0,
        onGround = flags and F_ON_GROUND != 0,
        isMoving = flags and F_MOVING != 0,
        isMovingForward = flags and F_MOVING != 0,
        isSprinting = flags and F_SPRINTING != 0,
        health = 20f,
        hurtTime = hurt,
        maxHurtResistantTime = 10,
        hurtResistantTime = hurt,
        attackCooldownProgress = 1f,
        isBlocking = false,
        isUsingItem = false,
        isSwinging = false,
        isLookingAtTarget = false,
        isMining = false,
        isSneaking = false,
        selectedSlot = 0,
        weaponType = WeaponType.SWORD,
        isAttackKeyDown = flags and F_ATTACK_KEY_DOWN != 0,
        ticks = tick
    )

    @Test
    fun `kotlin aim matches the rust port bit for bit`() {
        val dir = goldenDir()
        val inputFile = File(dir, "aim_input.tsv")
        val expectedFile = File(dir, "aim_expected.tsv")
        assertTrue(inputFile.isFile, "missing ${inputFile.absolutePath}")

        val seed = seedOf(inputFile)
        val inputs = readRows(inputFile)
        val expected = readRows(expectedFile)

        assertEquals(inputs.size * 2, expected.size, "expected two rows (one per mode) per tick")

        val legit = LegitAimStrategy()
        val adaptive = SelfAdaptiveAimStrategy()

        val legitState = AimStrategy.State()
        val adaptiveState = SelfAdaptiveAimStrategy.AdaptiveState()

        val legitRng = DecisionRng(seed xor 1L)
        val adaptiveRng = DecisionRng(seed xor 2L)

        val legitCfg = AimConfig()
        val adaptiveCfg = AimConfig()

        var checked = 0

        for ((rowIndex, row) in inputs.withIndex()) {
            val idx = row[0].toInt()
            val px = row[1].toDouble()
            val py = row[2].toDouble()
            val pz = row[3].toDouble()
            val yaw = row[4].toFloat()
            val pitch = row[5].toFloat()
            val flags = row[6].toInt()
            val hurt = row[7].toInt()
            val tx = row[8].toDouble()
            val ty = row[9].toDouble()
            val tz = row[10].toDouble()
            val tmx = row[11].toDouble()
            val tmy = row[12].toDouble()
            val tmz = row[13].toDouble()
            val thealth = row[14].toFloat()
            val thurt = row[15].toInt()
            val tid = row[16].toInt()
            val hb = Hitbox(
                row[17].toDouble(), row[18].toDouble(), row[19].toDouble(),
                row[20].toDouble(), row[21].toDouble(), row[22].toDouble()
            )

            val p = player(px, py, pz, yaw, pitch, flags, hurt, idx.toLong())
            val t = TargetState(
                entityId = tid,
                name = "",
                position = Vec3(tx, ty, tz),
                motionX = tmx,
                motionY = tmy,
                motionZ = tmz,
                health = thealth,
                hurtTime = thurt,
                hurtResistantTime = thurt,
                isMovingBackward = false,
                isGoingBack = false,
                isMovingTowardsPlayer = false,
                distance = Math.sqrt(
                    (tx - px) * (tx - px) + (ty - py) * (ty - py) + (tz - pz) * (tz - pz)
                ).toFloat(),
                hitbox = hb,
                id = tid
            )

            val input = AimInput(player = p, target = t)

            for (mode in 0..1) {
                val exp = expected[rowIndex * 2 + mode]
                assertEquals(idx, exp[0].toInt(), "fixture row order")
                assertEquals(mode, exp[1].toInt(), "fixture mode order")

                val result = if (mode == 0) {
                    legit.processTick(legitCfg, legitState, p, t, 0f, 0f, legitRng)
                } else {
                    adaptive.processTick(adaptiveCfg, adaptiveState, p, t, adaptiveRng)
                }

                val expectAction = exp[2].toInt()
                when (result) {
                    is AimResult.Skip -> assertEquals(
                        0, expectAction,
                        "tick $idx mode $mode: kotlin skipped, rust applied"
                    )
                    is AimResult.ApplyRotation -> {
                        assertEquals(
                            1, expectAction,
                            "tick $idx mode $mode: kotlin applied, rust skipped"
                        )
                        assertEquals(
                            exp[3].toULong(16).toLong(),
                            java.lang.Double.doubleToRawLongBits(result.worldPoint.x),
                            "tick $idx mode $mode world.x"
                        )
                        assertEquals(
                            exp[4].toULong(16).toLong(),
                            java.lang.Double.doubleToRawLongBits(result.worldPoint.y),
                            "tick $idx mode $mode world.y"
                        )
                        assertEquals(
                            exp[5].toULong(16).toLong(),
                            java.lang.Double.doubleToRawLongBits(result.worldPoint.z),
                            "tick $idx mode $mode world.z"
                        )
                        assertEquals(
                            exp[6].toUInt(16).toInt(),
                            java.lang.Float.floatToRawIntBits(result.fractionY),
                            "tick $idx mode $mode fractionY"
                        )
                        assertEquals(
                            exp[7].toUInt(16).toInt(),
                            java.lang.Float.floatToRawIntBits(result.fractionP),
                            "tick $idx mode $mode fractionP"
                        )
                    }
                }
                checked++
            }
        }

        assertEquals(inputs.size * 2, checked)
        println("aim parity: $checked decisions identical (${inputs.size} ticks x 2 modes)")
    }

    private companion object {
        const val F_ON_GROUND = 1
        const val F_MOVING = 2
        const val F_SPRINTING = 8
        const val F_ATTACK_KEY_DOWN = 256
    }
}
