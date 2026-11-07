package io.doppel.core.ipc

import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.option.AimMode
import io.doppel.contract.strategy.aim.AimConfig
import io.doppel.contract.strategy.aim.AimResult
import io.doppel.contract.strategy.aim.AimStrategy
import io.doppel.core.strategy.aim.LegitAimStrategy
import io.doppel.core.strategy.autoblock.RangeGate
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.core.strategy.click.ProbabilisticClickStrategy
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.core.strategy.combat.BlockArbiter
import io.doppel.core.strategy.movement.SocdResolver
import io.doppel.core.strategy.movement.StopTap
import io.doppel.core.strategy.movement.StrafeCorrector
import io.doppel.core.strategy.reach.ReachRaycast
import io.doppel.contract.strategy.tap.Side
import io.doppel.core.strategy.tap.StrafePattern
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import io.doppel.contract.strategy.click.ClickStrategy
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Endpoint
import io.doppel.core.rng.DecisionRng

/**
 * Proves the session boundary for every module, not just velocity.
 *
 * `AimParityTest` and its siblings compare the two *algorithms* through a golden
 * fixture. This compares the two *pipelines*: the same inputs are handed to the
 * in-process Kotlin strategy and to `CoreStub`, which encodes them, ships them
 * to a real `doppel-core` process, and decodes the answer. A codec that writes
 * the target's box at the wrong offset, or forgets the aim tail, passes every
 * unit test in the world and fails here.
 *
 * One core process is shared by all cases. Each module owns its own RNG stream
 * in the core, seeded from the session seed, which is what makes a per-module
 * comparison meaningful: a shared stream would make click's sequence depend on
 * how many ticks aim had already consumed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleSessionParityTest {

    private var proc: Process? = null
    private var stub: CoreStub? = null

    private fun locateCore(): File? = listOf(
        File("../core-rs/target/debug/doppel-core"),
        File("../core-rs/target/release/doppel-core"),
        File("Doppel/core-rs/target/debug/doppel-core")
    ).firstOrNull { it.canExecute() }

    @BeforeAll
    fun startCore() {
        val exe = locateCore()
        assumeTrue(
            exe != null,
            "doppel-core not built — run: cd Doppel/core-rs && cargo build. " +
                "The `core-ipc` CI job builds it and asserts these tests really ran."
        )
        val p = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "778899")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val banner = p.inputStream.bufferedReader().readLine()
        assertNotNull(banner, "core produced no LISTEN line")
        assertTrue(banner!!.startsWith("LISTEN "), "unexpected banner: $banner")
        proc = p
        val s = CoreStub(Endpoint.parse("tcp:" + banner.removePrefix("LISTEN ").trim()), null, deadlineNanos = 20_000_000L)
        s.connect()
        stub = s
    }

    @AfterAll
    fun stopCore() {
        try {
            stub?.close()
        } catch (_: Exception) {
        }
        proc?.destroyForcibly()
        proc?.waitFor(5, TimeUnit.SECONDS)
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun player(
        px: Double = 0.0, py: Double = 64.0, pz: Double = 0.0,
        yaw: Float = 0f, pitch: Float = 0f,
        hurt: Int = 0, mining: Boolean = false
    ) = PlayerState(
        name = "me",
        position = Vec3(px, py, pz),
        rotation = Vec2(yaw, pitch),
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        onGround = true, isMoving = true, isMovingForward = true, isSprinting = false,
        health = 20f, hurtTime = hurt, maxHurtResistantTime = 10, hurtResistantTime = hurt,
        isBlocking = false, isUsingItem = false, isLookingAtTarget = false,
        isMining = mining, isSneaking = false, selectedSlot = 0,
        weaponType = WeaponType.SWORD, isAttackKeyDown = false, ticks = 0L
    )

    private fun target(
        x: Double = 1.5, y: Double = 64.0, z: Double = 2.0,
        mx: Double = 0.0, mz: Double = 0.0,
        hurt: Int = 0, distance: Float = 2.5f
    ) = TargetState(
        entityId = 7, name = "them",
        position = Vec3(x, y, z),
        motionX = mx, motionY = 0.0, motionZ = mz,
        health = 20f, hurtTime = hurt, hurtResistantTime = hurt,
        isMovingBackward = false, isGoingBack = false, isMovingTowardsPlayer = false,
        distance = distance,
        hitbox = Hitbox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3),
        id = 7
    )

    private fun bits(d: Double) = java.lang.Double.doubleToRawLongBits(d)
    private fun bits(f: Float) = java.lang.Float.floatToRawIntBits(f)

    // ── aim ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `aim agrees across the socket`() {
        val stub = stub!!
        val config = AimConfig()   // defaults must equal `AimConfig::default()` in Rust
        val local = LegitAimStrategy()
        val state = AimStrategy.State()
        val rng = DecisionRng(stub.seed)
        val inputs = DecisionRng(0xA1A1_A1A1L)

        var applies = 0
        for (t in 0 until 300) {
            val p = player(
                yaw = inputs.randomFloat(-180f, 180f),
                pitch = inputs.randomFloat(-60f, 60f)
            )
            val tx = inputs.randomFloat(-3f, 3f).toDouble()
            val tz = inputs.randomFloat(1f, 5f).toDouble()
            val tg = target(x = tx, z = tz, mx = inputs.randomFloat(-0.2f, 0.2f).toDouble(),
                mz = inputs.randomFloat(-0.2f, 0.2f).toDouble())
            val mdx = inputs.randomFloat(-20f, 20f)
            val mdy = inputs.randomFloat(-20f, 20f)

            val mine = local.processTick(config, state, p, tg, mdx, mdy, rng)
            val remote = stub.decideAim(p, tg, mdx, mdy, 1.0f, t)
                ?: throw AssertionError("tick $t: core did not answer")

            when (mine) {
                is AimResult.Skip -> assertEquals(
                    false, remote.apply, "tick $t: kotlin skipped, core applied"
                )
                is AimResult.ApplyRotation -> {
                    applies++
                    assertTrue(remote.apply, "tick $t: kotlin applied, core skipped")
                    assertEquals(bits(mine.worldPoint.x), bits(remote.worldPoint.x), "tick $t world x")
                    assertEquals(bits(mine.worldPoint.y), bits(remote.worldPoint.y), "tick $t world y")
                    assertEquals(bits(mine.worldPoint.z), bits(remote.worldPoint.z), "tick $t world z")
                    assertEquals(bits(mine.fractionY), bits(remote.fractionY), "tick $t fraction y")
                    assertEquals(bits(mine.fractionP), bits(remote.fractionP), "tick $t fraction p")
                    assertEquals(mine.hitbox != null, remote.hitbox != null, "tick $t hitbox presence")
                    if (mine.hitbox != null && remote.hitbox != null) {
                        assertEquals(bits(mine.hitbox!!.minY), bits(remote.hitbox!!.minY), "tick $t box minY")
                        assertEquals(bits(mine.hitbox!!.maxY), bits(remote.hitbox!!.maxY), "tick $t box maxY")
                    }
                }
            }
        }
        assertTrue(applies > 100, "expected the apply path to fire, saw $applies")
        println("[aim] 300 ticks, $applies applied, ${stub.roundTrips.get()} round trips total")
    }

    // ── click ──────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `click agrees across the socket`() {
        val stub = stub!!
        val config = ClickConfig()
        val local = ProbabilisticClickStrategy()
        val state = io.doppel.contract.strategy.click.ClickStrategy.State()
        val rng = DecisionRng(stub.seed)
        val inputs = DecisionRng(0xB2B2_B2B2L)

        var clicks = 0
        for (t in 0 until 400) {
            val p = player(mining = inputs.randomFloat(0f, 1f) < 0.1f)
            val tg = if (inputs.randomFloat(0f, 1f) < 0.85f) target() else null

            val mine = local.processTick(config, state, p, tg, rng)
            val remote = stub.decideClick(p, tg, 1.0f, false, t)

            assertEquals(mine.javaClass.simpleName, remote.javaClass.simpleName, "tick $t")
            if (mine is ClickResult.Click) clicks++
        }
        assertTrue(clicks > 20, "expected clicks, saw $clicks")
        println("[click] 400 ticks, $clicks clicks")
    }

    // ── movement ───────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `movement agrees across the socket`() {
        val stub = stub!!
        val p = player()

        // SOCD: stateful, so a mismatch on tick N corrupts every later tick.
        val socd = SocdResolver()
        val keyRng = DecisionRng(0xC3C3_C3C3L)
        for (t in 0 until 300) {
            val f = keyRng.nextFloat() < 0.5f
            val b = keyRng.nextFloat() < 0.3f
            val l = keyRng.nextFloat() < 0.4f
            val r = keyRng.nextFloat() < 0.4f

            val mine = socd.resolve(f, b, l, r)
            val remote = stub.movementSocd(p, null, f, b, l, r, t)
                ?: throw AssertionError("tick $t: socd got no answer")

            assertEquals(mine.suppressForward, remote[0], "tick $t socd forward")
            assertEquals(mine.suppressBack, remote[1], "tick $t socd back")
            assertEquals(mine.suppressLeft, remote[2], "tick $t socd left")
            assertEquals(mine.suppressRight, remote[3], "tick $t socd right")
        }

        // Strafe corrector: pure, but its score runs through sin/cos/atan.
        val pRng = DecisionRng(0xD4D4_D4D4L)
        for (t in 0 until 200) {
            val input = StrafeCorrector.Input(
                forward = pRng.nextFloat() < 0.7f,
                back = pRng.nextFloat() < 0.2f,
                left = pRng.nextFloat() < 0.5f,
                right = pRng.nextFloat() < 0.5f
            )
            val s = StrafeCorrector.State(
                distance = pRng.randomFloat(1f, 6f).toDouble(),
                bearingRad = pRng.randomFloat(-3.14f, 3.14f).toDouble(),
                yawDeg = pRng.randomFloat(-180f, 180f).toDouble(),
                reach = 3.0
            )
            val mine = StrafeCorrector.correct(input, s)
            val remote = stub.movementStrafe(
                p, null,
                input.forward, input.back, input.left, input.right,
                s.distance, s.bearingRad, s.yawDeg, s.reach, t
            ) ?: throw AssertionError("tick $t: strafe got no answer")

            assertEquals(mine.suppressForward, remote[0], "tick $t strafe forward")
            assertEquals(mine.suppressBack, remote[1], "tick $t strafe back")
            assertEquals(mine.suppressLeft, remote[2], "tick $t strafe left")
            assertEquals(mine.suppressRight, remote[3], "tick $t strafe right")
        }

        // Instant stop.
        val sRng = DecisionRng(0xE5E5_E5E5L)
        for (t in 0 until 200) {
            val mx = sRng.randomFloat(-0.5f, 0.5f).toDouble()
            val mz = sRng.randomFloat(-0.5f, 0.5f).toDouble()
            val yaw = sRng.randomFloat(-180f, 180f)
            val rel = sRng.nextFloat() < 0.5f
            val mine = StopTap.stillSliding(mx, mz, yaw, rel)
            val remote = stub.movementSliding(p, null, mx, mz, yaw, rel, StopTap.MIN_SPEED, t)
                ?: throw AssertionError("tick $t: sliding got no answer")
            assertEquals(mine, remote, "tick $t sliding")
        }
        println("[movement] socd 300, strafe 200, sliding 200")
    }

    // ── tap ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `tap agrees across the socket`() {
        val stub = stub!!
        val p = player(yaw = 30f, px = 0.0, pz = 0.0)

        // wrap180: a while loop, so the number of iterations is part of the answer.
        for (t in 0 until 200) {
            val angle = (t - 100) * 37.3f
            val mine = StrafePattern.wrap180(angle)
            val remote = stub.tapWrap(p, null, angle, t)
                ?: throw AssertionError("tick $t: wrap got no answer")
            assertEquals(bits(mine), bits(remote.toFloat()), "tick $t wrap180($angle)")
        }

        // chaseSide: the bearing goes through atan2 on both sides.
        val cRng = DecisionRng(0xF6F6_F6F6L)
        for (t in 0 until 200) {
            val tg = target(
                x = cRng.randomFloat(-4f, 4f).toDouble(),
                z = cRng.randomFloat(-4f, 4f).toDouble(),
                mx = cRng.randomFloat(-0.3f, 0.3f).toDouble(),
                mz = cRng.randomFloat(-0.3f, 0.3f).toDouble()
            )
            val yaw = cRng.randomFloat(-180f, 180f)
            val lead = cRng.randomInt(0, 20)
            val mine = StrafePattern.chaseSide(yaw, p.position, tg, lead)
            val remote = stub.tapChase(p, tg, yaw, lead, t)
                ?: throw AssertionError("tick $t: chase got no answer")
            assertEquals(
                (if (mine == Side.LEFT) 0.0 else 1.0), remote,
                "tick $t chaseSide"
            )
        }

        // sevenTapSide: consumes the RNG, so a wrong call order desynchronises
        // every later draw — which is exactly what this catches.
        val rng = DecisionRng(stub.seed)
        var side = Side.LEFT
        for (t in 0 until 300) {
            val chance = 30
            val mine = StrafePattern.sevenTapSide(side, chance, rng)
            val remote = stub.tapSeven(p, null, if (side == Side.LEFT) 0 else 1, chance, t)
                ?: throw AssertionError("tick $t: seven got no answer")
            assertEquals((if (mine == Side.LEFT) 0.0 else 1.0), remote, "tick $t sevenTapSide")
            side = mine
        }
        println("[tap] wrap 200, chase 200, seven 300")
    }

    // ── reach and combat ───────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `reach and combat agree across the socket`() {
        val stub = stub!!
        val p = player()

        // Reach: the slab test divides by the direction component without a guard.
        val rRng = DecisionRng(0x1A1A_1A1AL)
        for (t in 0 until 200) {
            val origin = Vec3(
                rRng.randomFloat(-1f, 1f).toDouble(),
                64.0,
                rRng.randomFloat(-1f, 1f).toDouble()
            )
            val dir = Vec3(
                rRng.randomFloat(-1f, 1f).toDouble(),
                rRng.randomFloat(-0.2f, 0.2f).toDouble(),
                rRng.randomFloat(-1f, 1f).toDouble()
            ).normalize()
            val box = Hitbox(-0.5, 63.5, 1.5, 0.5, 65.5, 3.5)
            val maxDist = 6.0
            val mine = ReachRaycast.intersectBox(origin, dir, Vec3(box.minX, box.minY, box.minZ),
                Vec3(box.maxX, box.maxY, box.maxZ), maxDist) != null
            val remote = stub.reachIntersect(p, null, box, origin, dir, maxDist, t)
                ?: throw AssertionError("tick $t: reach got no answer")
            assertEquals(mine, remote, "tick $t reach intersect")
        }

        // Range gate: a latch, so order matters.
        val gateState = RangeGate.State()
        val gRng = DecisionRng(0x2B2B_2B2BL)
        val cfg = RangeGate.Config(RangeGate.Mode.ON_ENTER, 1.0f, 3.0f, 1.5f)
        for (t in 0 until 200) {
            val d: Float? = if (gRng.nextFloat() < 0.1f) null else gRng.randomFloat(0f, 5f)
            val mine = RangeGate.compute(gateState, cfg, d)
            val remote = stub.combatRange(p, null, 1, cfg.minDistance, cfg.maxDistance, cfg.hysteresis, d, t)
                ?: throw AssertionError("tick $t: range gate got no answer")
            assertEquals(mine, remote, "tick $t range gate d=$d")
        }

        // Arbiter: duty cycle is state inside the core; the local one is fed the
        // same way so the two windows stay aligned.
        val arbiter = BlockArbiter.State(60)
        val aRng = DecisionRng(0x3C3C_3C3CL)
        val w = BlockArbiter.Weights()
        for (t in 0 until 200) {
            val locked = aRng.nextFloat() < 0.5f
            val threat = aRng.nextFloat() < 0.5f
            val sustain = aRng.nextFloat() < 0.4f
            val reflex = aRng.nextFloat() < 0.3f
            val mine = BlockArbiter.evaluate(locked, threat, sustain, reflex, w, 0.5, arbiter.duty(), 0.8)
            arbiter.record(mine.block)
            val remote = stub.combatArbiter(
                p, null, locked, threat, sustain, reflex,
                w.lock, w.threat, w.sustain, w.reflex, 0.5, 0.8, t
            ) ?: throw AssertionError("tick $t: arbiter got no answer")
            assertEquals(mine.block, remote.block, "tick $t arbiter block")
            assertEquals(bits(mine.score), bits(remote.score), "tick $t arbiter score")
        }
        println("[reach/combat] reach 200, gate 200, arbiter 200")
    }
}
