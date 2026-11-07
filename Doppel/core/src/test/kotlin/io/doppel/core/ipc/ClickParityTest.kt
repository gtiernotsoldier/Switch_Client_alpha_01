package io.doppel.core.ipc

import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickInput
import io.doppel.contract.strategy.click.ClickOperatingMode
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.ClickStrategy
import io.doppel.contract.strategy.click.CooldownClickConfig
import io.doppel.contract.strategy.click.CooldownClickMode
import io.doppel.core.strategy.click.CooldownClickStrategy
import io.doppel.contract.strategy.click.CritMode
import io.doppel.core.strategy.click.ProbabilisticClickStrategy
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.option.ClickMode
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import io.doppel.contract.strategy.click.CritState
import io.doppel.core.rng.DecisionRng

/**
 * Click parity against the Rust port, replaying `core-rs --emit-golden` fixtures.
 *
 * Five configurations run against the same 800 snapshots, each with its own
 * state and RNG stream. The crit state machine and DOUBLE mode both span
 * multiple ticks, so this is the test that catches an off-by-one phase
 * transition — a bug that still produces clicks, just never crits.
 */
class ClickParityTest {

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
            .filter { it.isNotBlank() && !it.startsWith("#") && it[0].isDigit() }
            .map { it.split("\t").toTypedArray() }

    private fun seedOf(f: File): Long =
        f.readLines().first { it.startsWith("# seed ") }.removePrefix("# seed ").trim().toLong()

    private fun player(flags: Int, hurt: Int, tick: Long): PlayerState = PlayerState(
        name = "",
        position = Vec3(0.0, 64.0, 0.0),
        rotation = Vec2(0f, 0f),
        motionX = 0.0,
        motionY = 0.0,
        motionZ = 0.0,
        onGround = flags and F_ON_GROUND != 0,
        isMoving = true,
        isMovingForward = true,
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
        isMining = flags and F_MINING != 0,
        isSneaking = false,
        selectedSlot = 0,
        weaponType = WeaponType.SWORD,
        isAttackKeyDown = flags and F_ATTACK_KEY_DOWN != 0,
        ticks = tick
    )

    @Test
    fun `kotlin click matches the rust port for all five configurations`() {
        val dir = goldenDir()
        val inputFile = File(dir, "click_input.tsv")
        val expectedFile = File(dir, "click_expected.tsv")
        assertTrue(inputFile.isFile, "missing ${inputFile.absolutePath}")

        val seed = seedOf(inputFile)
        val inputs = readRows(inputFile)
        val expected = readRows(expectedFile)
        assertEquals(inputs.size * 5, expected.size, "five strategy rows per tick")

        val probNormal = ProbabilisticClickStrategy()
        val probLegit = ProbabilisticClickStrategy()
        val cdOff = CooldownClickStrategy()
        val cdOn = CooldownClickStrategy()
        val cdSmart = CooldownClickStrategy()

        val st0 = ClickStrategy.State()
        val st1 = ClickStrategy.State()
        val st2 = CritState()
        val st3 = CritState()
        val st4 = CritState()

        val rng0 = DecisionRng(seed xor 0x10L)
        val rng1 = DecisionRng(seed xor 0x11L)
        val rng2 = DecisionRng(seed xor 0x12L)
        val rng3 = DecisionRng(seed xor 0x13L)
        val rng4 = DecisionRng(seed xor 0x14L)

        val cfg0 = ClickConfig()
        val cfg1 = ClickConfig(
            minCps = 6,
            maxCps = 12,
            mode = ClickOperatingMode.LEGIT,
            clickMode = ClickMode.DOUBLE
        )
        val cfg2 = CooldownClickConfig()
        val cfg3 = CooldownClickConfig(
            cooldownThreshold = 0.9f,
            critMode = CritMode.ON,
            cooldownMode = CooldownClickMode.LEGIT
        )
        val cfg4 = CooldownClickConfig(critMode = CritMode.SMART)

        var checked = 0

        for ((rowIndex, row) in inputs.withIndex()) {
            val idx = row[0].toInt()
            val flags = row[1].toInt()
            val cooldown = row[2].toFloat()
            val falling = row[3].toInt() == 1
            val hasTarget = row[4].toInt() == 1
            val tx = row[5].toDouble()
            val tz = row[6].toDouble()
            val tdist = row[7].toFloat()
            val hurt = row[8].toInt()

            val p = player(flags, hurt, idx.toLong())
            val t = if (!hasTarget) null else TargetState(
                entityId = 5,
                name = "",
                position = Vec3(tx, 64.0, tz),
                motionX = 0.0,
                motionY = 0.0,
                motionZ = 0.0,
                health = 20f,
                hurtTime = 0,
                hurtResistantTime = 0,
                isMovingBackward = false,
                isGoingBack = false,
                isMovingTowardsPlayer = false,
                distance = tdist,
                hitbox = Hitbox(tx - 0.3, 64.0, tz - 0.3, tx + 0.3, 65.8, tz + 0.3),
                id = 5
            )
            val clickInput = ClickInput(player = p, target = t, attackCooldown = cooldown, isFalling = falling)

            val results = listOf(
                probNormal.processTick(cfg0, st0, p, t, rng0),
                probLegit.processTick(cfg1, st1, p, t, rng1),
                cdOff.processTick(cfg2, st2, clickInput, rng2),
                cdOn.processTick(cfg3, st3, clickInput, rng3),
                cdSmart.processTick(cfg4, st4, clickInput, rng4)
            )

            for (sid in 0..4) {
                val exp = expected[rowIndex * 5 + sid]
                assertEquals(idx, exp[0].toInt(), "fixture row order")
                assertEquals(sid, exp[1].toInt(), "fixture strategy order")
                assertEquals(
                    exp[2].toInt(),
                    code(results[sid]),
                    "tick $idx strategy $sid"
                )
                checked++
            }
        }

        assertEquals(inputs.size * 5, checked)
        println("click parity: $checked decisions identical (${inputs.size} ticks x 5 configurations)")
    }

    private fun code(r: ClickResult): Int = when (r) {
        is ClickResult.Skip -> 0
        is ClickResult.Click -> 1
        is ClickResult.StopSprint -> 2
        is ClickResult.RestoreSprint -> if (r.wasSprinting) 3 else 4
    }

    private companion object {
        const val F_ON_GROUND = 1
        const val F_SPRINTING = 8
        const val F_ATTACK_KEY_DOWN = 256
        const val F_MINING = 512
    }
}
