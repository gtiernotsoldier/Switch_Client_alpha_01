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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Endpoint
import io.doppel.ipc.Ops
import io.doppel.core.rng.DecisionRng

/**
 * Full-loop test: spawn the real `doppel-core` binary, connect over TCP exactly
 * as the game will, and compare every decision against the in-process Kotlin
 * strategy running on the same seed.
 *
 * This is the one test that exercises the whole boundary at once — framing,
 * handshake, seed agreement, config defaults, strategy parity, and the deadline
 * path. The unit tests around it each prove one link; this proves the chain.
 *
 * Skipped when the Rust binary is absent, but never silently: the JUnit report
 * carries the reason, and the `core-ipc` CI job builds the binary and then
 * asserts the test *ran* rather than skipped. Without that second check a
 * mistyped path could leave both jobs green and nothing exercised.
 */
class CoreStubE2ETest {

    private fun locateCore(): File? = listOf(
        File("../core-rs/target/debug/doppel-core"),
        File("../core-rs/target/release/doppel-core"),
        File("Doppel/core-rs/target/debug/doppel-core")
    ).firstOrNull { it.canExecute() }

    private fun player() = PlayerState(
        name = "me",
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
        VelocityResult.NoOp -> 0
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `stub and core agree tick for tick over a real socket`() {
        val exe = locateCore()
        assumeTrue(
            exe != null,
            "doppel-core not built — run: cd Doppel/core-rs && cargo build. " +
                "The `core-ipc` CI job builds it and asserts this test really ran."
        )
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            // Generous deadline: this asserts correctness, not latency. The
            // 1 ms production budget is measured by `doppel-core --bench`.
            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                assertTrue(stub.seed != 0L, "handshake did not deliver a seed")

                val local = LegitVelocityStrategy(DecisionRng(stub.seed))
                val state = VelocityStrategy.State()
                val config = VelocityConfig()

                // Inputs come from their own generator so they cannot perturb
                // either side's decision sequence.
                val inputs = DecisionRng(0x5A5A_5A5A_5A5A_5A5AL)
                val ticks = 500
                var modifies = 0

                for (t in 0 until ticks) {
                    val ctx = VelocityContext(
                        originalMotion = Vec3(
                            inputs.randomFloat(-1f, 1f).toDouble(),
                            inputs.randomFloat(0f, 0.5f).toDouble(),
                            inputs.randomFloat(-1f, 1f).toDouble()
                        ),
                        player = player(),
                        target = null,
                        packetHandle = Unit,
                        isKnockbackHit = true
                    )

                    val remote = stub.decide(ctx, t)
                    val mine = local.execute(config, state, ctx)

                    assertEquals(
                        actionCode(mine), actionCode(remote),
                        "tick $t: kotlin=${actionCode(mine)} core=${actionCode(remote)}"
                    )

                    if (mine is VelocityResult.Modify) {
                        modifies++
                        val r = remote as VelocityResult.Modify
                        assertEquals(
                            java.lang.Double.doubleToRawLongBits(mine.motion.x),
                            java.lang.Double.doubleToRawLongBits(r.motion.x),
                            "tick $t motion.x diverged"
                        )
                        assertEquals(
                            java.lang.Double.doubleToRawLongBits(mine.motion.y),
                            java.lang.Double.doubleToRawLongBits(r.motion.y),
                            "tick $t motion.y diverged"
                        )
                        assertEquals(
                            java.lang.Double.doubleToRawLongBits(mine.motion.z),
                            java.lang.Double.doubleToRawLongBits(r.motion.z),
                            "tick $t motion.z diverged"
                        )
                    }
                }

                assertEquals(0L, stub.timeouts.get(), "deadline misses during a loopback run")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks during a healthy run")
                assertTrue(modifies > 100, "expected the reduce path to fire, saw $modifies")
                println(
                    "[e2e] $ticks ticks, $modifies reduced, " +
                        "${stub.roundTrips.get()} round trips, session=${stub.sessionId}"
                )
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `eagle place decisions travel intact over a real socket`() {
        // The unit tests cover the strategy and the wire codec separately; this
        // one proves the whole path for the newest player op: Kotlin marshalling
        // → TCP → decode_generic → on_player dispatch → eagle_place::tick →
        // encode_scalar → decodeScalar. The step sequence is checked against the
        // Kotlin oracle using the same scripted cooldown roll (the two RNGs are
        // different generators, so the draw itself is not compared).
        val exe = locateCore()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                val p = player()
                var tick = 1

                // A pitch already at the angle clicks and arms a cooldown.
                val click = stub.playerEaglePlace(p, null, true, true, false, 3, 2, 2, tick++)
                assertEquals(2, click, "CLICK over the socket")

                // Cooling down: NONE, no rotation and no click.
                val cooling = stub.playerEaglePlace(p, null, true, false, false, 3, 2, 2, tick++)
                assertEquals(0, cooling, "NONE while cooling")

                // Spend the remaining cooldown, then AIM.
                stub.playerEaglePlace(p, null, true, false, false, 3, 2, 2, tick++)
                val aim = stub.playerEaglePlace(p, null, true, false, false, 3, 2, 2, tick++)
                assertEquals(1, aim, "AIM once the cooldown is spent")

                // A closed gate cancels the pending aim and forgets the cooldown.
                stub.playerEaglePlace(p, null, false, false, false, 3, 2, 2, tick++)
                val clickAgain = stub.playerEaglePlace(p, null, true, true, false, 3, 2, 2, tick)
                assertEquals(2, clickAgain, "CLICK again after the reset")

                assertEquals(0L, stub.timeouts.get(), "deadline misses on loopback")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks on a healthy run")
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `sprint reset decisions travel intact over a real socket`() {
        // The whole path for the SprintReset op: Kotlin marshalling → TCP →
        // decode_generic → on_player dispatch → sprint_reset::tick →
        // encode_scalar → decodeScalar. chance=100 skips the draw, so the
        // step sequence is deterministic across the two different RNGs.
        val exe = locateCore()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                val p = player()
                var tick = 1
                var now = 1_000_000_000L

                // hurt low (no edge) -> NONE; rising edge -> SEND_NOSTOP.
                val idle = stub.playerSprintReset(
                    p, null, now, true, true, true, false, 7, 100, 1, 0, 0, tick++
                )
                assertEquals(0, idle, "no send without a fresh hit")
                now += 50_000_000L
                val send = stub.playerSprintReset(
                    p, null, now, true, true, true, true, 7, 100, 1, 0, 0, tick++
                )
                assertEquals(1, send, "SEND_NOSTOP on the rising edge over the socket")

                // A delayed send: new target id so the edge is fresh. It arms
                // on the edge, stays quiet before the deadline, then fires with
                // the captured Silent mode at the deadline.
                now += 50_000_000L
                val armed = stub.playerSprintReset(
                    p, null, now, true, true, true, true, 9, 100, 1, 100, 1, tick++
                )
                assertEquals(0, armed, "armed, nothing sent yet")
                now += 50_000_000L
                val waiting = stub.playerSprintReset(
                    p, null, now, true, true, true, true, 9, 100, 1, 100, 1, tick++
                )
                assertEquals(0, waiting, "still quiet before the deadline")
                now += 50_000_000L
                val fired = stub.playerSprintReset(
                    p, null, now, true, true, true, true, 9, 100, 1, 100, 1, tick
                )
                assertEquals(2, fired, "SEND_SILENT fires at the deadline")

                assertEquals(0L, stub.timeouts.get(), "deadline misses on loopback")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks on a healthy run")
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `jump reset knock and pump decisions travel intact over a real socket`() {
        // The whole path for the two JumpReset ops: Kotlin marshalling → TCP →
        // decode_generic → on_player dispatch → jump_reset::knock / pump →
        // encode_scalar → decodeScalar. chance=100 skips the draw, so the step
        // sequence is deterministic across the two different RNGs.
        val exe = locateCore()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                val p = player()
                var tick = 1
                var now = 1_000_000_000L

                // ReceivedHits, hits_until=1, chance=100, delay=0 → JUMP now.
                val immediate = stub.playerJumpKnock(p, null, true, now, 100, 1, 0, 1, 0, tick++)
                assertEquals(1, immediate, "JUMP now over the socket")

                // Closed gates: no jump, and the state stays untouched.
                now += 50_000_000L
                val closed = stub.playerJumpKnock(p, null, false, now, 100, 1, 0, 1, 0, tick++)
                assertEquals(0, closed, "closed gates never jump")

                // A delayed jump arms on knock and fires at its deadline on
                // pump — the two halves share one core state.
                now += 50_000_000L
                val armed = stub.playerJumpKnock(p, null, true, now, 100, 1, 0, 1, 100, tick++)
                assertEquals(0, armed, "armed, nothing yet")
                now += 50_000_000L
                val waiting = stub.playerJumpPump(p, null, now, 0, 4, tick++)
                assertEquals(0, waiting, "still quiet before the deadline")
                now += 50_000_000L
                val fired = stub.playerJumpPump(p, null, now, 0, 4, tick)
                assertEquals(1, fired, "JUMP at the deadline over the socket")

                assertEquals(0L, stub.timeouts.get(), "deadline misses on loopback")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks on a healthy run")
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `super knockback decisions travel intact over a real socket`() {
        // The whole path for the SuperKnockback op: Kotlin marshalling → TCP →
        // decode_generic → on_player dispatch → super_knockback::tick →
        // encode_scalar → decodeScalar. chance=100 skips the draw, so the
        // packed step codes are deterministic across the two different RNGs.
        val exe = locateCore()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                val p = player()
                var tick = 1
                var now = 1_000_000_000L

                // Old, chance=100, delay=0 → fire step 5 in the high nibble.
                val old = stub.playerSuperKnockback(
                    p, null, now, true, true, true, true, 7, 100, 1, 0, 1, false, false, tick++
                )
                assertEquals(80, old, "OLD fire over the socket")

                // SprintTap arms the force sequence; the next tick advances it
                // while the trigger stays quiet — the low nibble must survive.
                now += 50_000_000L
                val armed = stub.playerSuperKnockback(
                    p, null, now, true, true, true, true, 7, 100, 1, 0, 0, true, false, tick++
                )
                assertEquals(64, armed, "SPRINT_TAP fire arms the sequence")
                now += 50_000_000L
                val advancing = stub.playerSuperKnockback(
                    p, null, now, true, false, true, true, 7, 100, 1, 0, 0, false, false, tick++
                )
                assertEquals(1, advancing, "BREAK step travels in the low nibble")

                // A delayed SneakPacket fire arms on the hit and fires at its
                // deadline on a later tick, through the same shared state.
                now += 50_000_000L
                val delayed = stub.playerSuperKnockback(
                    p, null, now, true, true, true, true, 7, 100, 1, 100, 2, false, false, tick++
                )
                assertEquals(0, delayed, "armed, nothing yet")
                now += 50_000_000L
                val waiting = stub.playerSuperKnockback(
                    p, null, now, true, false, true, true, 7, 100, 1, 100, 2, false, false, tick++
                )
                assertEquals(0, waiting, "still quiet before the deadline")
                now += 50_000_000L
                val fired = stub.playerSuperKnockback(
                    p, null, now, true, false, true, true, 7, 100, 1, 100, 2, false, false, tick
                )
                assertEquals(96, fired, "SNEAK_PACKET fires at the deadline")

                assertEquals(0L, stub.timeouts.get(), "deadline misses on loopback")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks on a healthy run")
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `tap range samples travel intact over a real socket`() {
        // The whole path for the WTap / STap sampling op: Kotlin marshalling →
        // TCP → decode_generic → on_tap dispatch → tap_sample::sample →
        // encode_scalar → decodeScalar. Degenerate ranges draw nothing, so the
        // packed codes are deterministic across the two different RNGs.
        val exe = locateCore()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "424242")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assertNotNull(banner, "core produced no LISTEN line")
            assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 20_000_000L).use { stub ->
                stub.connect()
                val p = player()
                var tick = 1

                val fire = stub.tapSample(
                    Ops.TAP_WTAP_SAMPLE, p, null, 0, 0, 0, 40, 40, 1, 1, tick++
                )
                assertEquals(40 * 1024.0, fire!!, "post-delay 0, duration 40 over the socket")

                val action = stub.tapSample(
                    Ops.TAP_STAP_SAMPLE, p, null, 1, 0, 0, 25, 25, 1, 1, tick++
                )
                assertEquals(25 * 1024.0, action!!, "ACTION draws only the duration")

                val init = stub.tapSample(
                    Ops.TAP_WTAP_SAMPLE, p, null, 2, 0, 0, 0, 0, 3, 3, tick++
                )
                assertEquals((3 * 1024 * 1024).toDouble(), init!!, "INIT seeds the threshold")

                // A real random range still lands inside the configured band.
                val random = stub.tapSample(
                    Ops.TAP_STAP_SAMPLE, p, null, 1, 0, 0, 10, 20, 1, 1, tick
                )!!
                val ms = (random.toInt() / 1024) % 1024
                assertTrue(ms in 10..20, "duration stays in [10, 20], got $ms")

                assertEquals(0L, stub.timeouts.get(), "deadline misses on loopback")
                assertEquals(0L, stub.degradedTicks.get(), "degraded ticks on a healthy run")
            }
        } finally {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `a dead core degrades instead of throwing`() {
        // Bind, then close: the stub must treat a refused connection as
        // "degrade this tick", never as an exception escaping into the tick loop.
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        server.close()

        val stub = CoreStub(Endpoint.Tcp("127.0.0.1", port), null, deadlineNanos = 2_000_000L)
        val ctx = VelocityContext(Vec3(1.0, 0.4, 1.0), player(), null, Unit)
        val result = stub.decide(ctx, 1)
        assertTrue(result is VelocityResult.Pass, "a dead core must yield Pass(original), got $result")
        assertTrue(stub.degradedTicks.get() > 0)
        stub.close()
    }
}
