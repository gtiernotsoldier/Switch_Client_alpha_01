package io.doppel.core.ipc

import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.option.TriggerOptions
import io.doppel.contract.strategy.aim.AimConfig
import io.doppel.contract.strategy.aim.AimInput
import io.doppel.contract.strategy.aim.AimResult
import io.doppel.contract.strategy.aim.AimStrategy
import io.doppel.contract.util.Vec3
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreSupervisor
import io.doppel.ipc.Strategies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The whole chain, against a real process: supervisor spawns `doppel-core`, the
 * banner gives up the ephemeral port, the handshake completes, [Strategies] hands
 * the adapter a strategy that actually talks to it, and stopping detaches it again.
 *
 * ## Why this is not a unit test with a fake stub
 *
 * The failure this guards against is not "does encodeSnapshot produce 232 bytes" —
 * the parity tests already cover the wire. It is "does anything at all assign
 * [CoreLink.stub]". For one review round it did not: `RemoteAimStrategy` existed,
 * was tested, and was unreachable from the adapter, so the algorithm could be
 * perfectly correct and never run. A fake stub cannot catch that, because a fake
 * does not need a supervisor to exist.
 */
class CoreSupervisorTest {

    @AfterEach
    fun tearDown() {
        CoreSupervisor.stop()
        System.clearProperty("doppel.core.path")
    }

    private fun binary(): File? = listOf(
        "core-rs/target/debug/doppel-core",
        "core-rs/target/release/doppel-core",
        "../core-rs/target/debug/doppel-core",
        "../core-rs/target/release/doppel-core",
        "Doppel/core-rs/target/debug/doppel-core"
    ).map(::File).firstOrNull { it.isFile && it.canExecute() }

    @Test
    fun `spawns a core and the factory returns a strategy that reaches it`() {
        val exe = binary()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        System.setProperty("doppel.core.path", exe!!.absolutePath)

        assertTrue(CoreSupervisor.start(), "supervisor refused to start ${exe.absolutePath}")
        assertTrue(Strategies.online, "stub was not published to CoreLink")

        val strategy = Strategies.aim()
        assertTrue(strategy is io.doppel.ipc.RemoteAimStrategy, "factory returned ${strategy.name}")

        // One real round trip. The answer itself is not asserted: whether the
        // crosshair moves depends on the aim config and the target geometry, and
        // pinning that here would duplicate AimParityTest. What matters is that a
        // frame went out, came back, and decoded.
        val player = PlayerState(
            name = "tester",
            posX = 0.0, posY = 64.0, posZ = 0.0,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            rotationYaw = 0f, rotationPitch = 0f,
            onGround = true, isMovingForward = false, isSprinting = false,
            health = 20f, hurtTime = 0, ticks = 1L
        )
        val target = TargetState(
            entityId = 7, name = "target",
            posX = 0.0, posY = 64.0, posZ = 3.0,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            health = 20f, hurtTime = 0, distance = 3.0f,
            hitbox = Hitbox(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3)
        )
        val input = AimInput(player, target, mouseDeltaX = 0f, mouseDeltaY = 0f, sensitivity = 0.5f)

        val result = strategy.execute(AimConfig(), AimStrategy.State(), input)
        assertNotNull(result)
        assertTrue(result is AimResult.ApplyRotation || result is AimResult.Skip)
    }

    /**
     * The gate op, end to end.
     *
     * This is the one test that would catch a mis-packed flag: `onlyAir` has to travel as bit 1
     * of the request argument and come back as "blocked" for a player who is standing on the
     * ground. A layout that drifted by one position still compiles on both sides and still
     * returns a boolean — it just answers about a different condition.
     */
    @Test
    fun `the condition gate answers through the real core`() {
        val exe = binary()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        System.setProperty("doppel.core.path", exe!!.absolutePath)
        assertTrue(CoreSupervisor.start())

        val player = PlayerState(
            name = "tester",
            posX = 0.0, posY = 64.0, posZ = 0.0,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            rotationYaw = 0f, rotationPitch = 0f,
            onGround = true, isMovingForward = false, isSprinting = false,
            health = 20f, hurtTime = 0, ticks = 1L
        )
        val target = TargetState(
            entityId = 7, name = "target",
            posX = 0.0, posY = 64.0, posZ = 3.0,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0,
            health = 20f, hurtTime = 0, distance = 3.0f,
            hitbox = Hitbox(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3)
        )

        // No conditions set: the gate is open.
        assertTrue(Strategies.gate(player, target, TriggerOptions()))

        // onlyAir with an on-ground player: blocked. If the bit field were shifted this would
        // still return a boolean, and it would be the wrong one.
        assertFalse(Strategies.gate(player, target, TriggerOptions(onlyAir = true)))

        // onlyGround holds for the same player.
        assertTrue(Strategies.gate(player, target, TriggerOptions(onlyGround = true)))

        // decide() with the hurt window disabled, no chance gate and a threshold of one: fires.
        val d = Strategies.decide(
            player, target, TriggerOptions(),
            mode = 0, hurtGate = false, maxHurtTime = 10, targetHurtResistantTime = 0,
            hitCounter = 0, hitThreshold = 1, hitPerMin = 1, hitPerMax = 1, triggerChance = 100
        )
        assertNotNull(d)
        assertTrue(d!!.fire, "a POST trigger with hurtGate=false and threshold=1 must fire")
        assertTrue(d.counter == 0, "the counter resets after a fire, got ${d.counter}")
    }

    @Test
    fun `stopping detaches the stub and the factory falls back to inert`() {
        val exe = binary()
        assumeTrue(exe != null, "doppel-core not built — run: cd Doppel/core-rs && cargo build")
        System.setProperty("doppel.core.path", exe!!.absolutePath)

        assertTrue(CoreSupervisor.start())
        CoreSupervisor.stop()

        assertFalse(Strategies.online, "stub survived stop()")
        assertTrue(CoreLink.stub == null)
        // The off-line strategy must not be a Remote one: a Remote with a dead
        // socket would burn the round-trip deadline on every tick before skipping.
        assertFalse(Strategies.aim() is io.doppel.ipc.RemoteAimStrategy)
    }
}
