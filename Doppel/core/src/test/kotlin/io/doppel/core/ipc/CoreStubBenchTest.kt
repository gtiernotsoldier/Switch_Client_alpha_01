package io.doppel.core.ipc

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Endpoint
import io.doppel.ipc.Frame
import io.doppel.ipc.Wire
import io.doppel.ipc.genericRequest
import io.doppel.ipc.encodeSnapshot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Latency and marshalling-cost benchmark for the stub side of the IPC path.
 *
 * Not a correctness gate — every assertion here is a loose sanity bound that a
 * healthy loopback machine passes with two orders of magnitude to spare. The
 * interesting output is the printed distribution, which is the "before/after"
 * evidence for marshalling-path optimisation commits (task: architecture
 * optimisation, per the handoff queue item 1 — IPC path / module registration /
 * wire codec overhead).
 *
 * Two measurements, on purpose:
 *
 *  1. `codec` — encodeSnapshot + genericRequest + Frame.encode, no socket. This
 *     is the pure JVM-side marshalling cost the stub adds on top of the wire
 *     round trip (PROTECTION.md 9.5.1 budgets 5 µs for it).
 *  2. `roundtrip` — one real `movementSocd` decision through the socket,
 *     including the codec, the write, the core's dispatch and the reader-thread
 *     hand-back. Comparable to `doppel-core --bench`, but measured from the JVM
 *     side where the production stub lives.
 *
 * Skipped when the Rust binary is absent, exactly like [CoreStubE2ETest] — CI
 * builds the binary and asserts the test ran.
 */
class CoreStubBenchTest {

    private fun locateCore(): File? = listOf(
        File("../core-rs/target/debug/doppel-core"),
        File("../core-rs/target/release/doppel-core"),
        File("Doppel/core-rs/target/debug/doppel-core")
    ).firstOrNull { it.canExecute() }

    private fun player(): PlayerState = PlayerState(
        name = "me",
        position = Vec3(0.0, 64.0, 0.0),
        rotation = Vec2(0f, 0f),
        motionX = 0.1,
        motionY = 0.0,
        motionZ = 0.1,
        onGround = true,
        isMoving = true,
        isMovingForward = true,
        isSprinting = true,
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

    private fun target(): TargetState = TargetState(
        entityId = 42,
        name = "them",
        position = Vec3(1.0, 64.0, 0.5),
        motionX = -0.05,
        motionY = 0.0,
        motionZ = 0.02,
        health = 18f,
        hurtTime = 0,
        hurtResistantTime = 0,
        isMovingBackward = false,
        isGoingBack = false,
        isMovingTowardsPlayer = false,
        distance = 1.14f,
        hitbox = io.doppel.contract.model.Hitbox(0.7, 63.9, -0.3, 1.3, 65.5, 0.3),
        id = 42
    )

    private fun percentiles(sorted: LongArray, vararg ps: Double): String {
        val parts = ps.map { p ->
            val idx = ((sorted.size - 1) * p / 100.0).toInt().coerceAtMost(sorted.size - 1)
            "p" + (if (p == p.toInt().toDouble()) "%d".format(p.toInt()) else "%.1f".format(p)) +
                "=%,d ns".format(sorted[idx])
        }
        return parts.joinToString("  ") + "  max=%,d ns".format(sorted.last())
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `codec and roundtrip cost on the stub side`() {
        // ── 1. codec chain, no socket ────────────────────────────────────────────
        // The same per-call chain every generic round trip pays today: snapshot
        // buffer, generic-request buffer with the snapshot copied in, Frame with
        // its payload copy, and the encoded 384-byte wire buffer.
        val p = player()
        val t = target()
        val WARM = 5_000
        val N = 100_000
        repeat(WARM) {
            Frame(Wire.KIND_SNAPSHOT, 1, 1, module = Wire.MODULE_MOVEMENT,
                payload = genericRequest(encodeSnapshot(p, t), 1, 1.0, 0.0, 1.0, 0.0),
                payloadLen = Wire.SLOT_SNAPSHOT + 2 + 4 * 8).encode()
        }
        val codecNs = LongArray(N)
        for (i in 0 until N) {
            val start = System.nanoTime()
            val payload = genericRequest(encodeSnapshot(p, t), 1, 1.0, 0.0, 1.0, 0.0)
            val frame = Frame(Wire.KIND_SNAPSHOT, i, i, module = Wire.MODULE_MOVEMENT,
                payload = payload, payloadLen = payload.size)
            frame.encode()
            codecNs[i] = System.nanoTime() - start
        }
        codecNs.sort()
        println("[bench] codec chain (encodeSnapshot+genericRequest+Frame.encode), n=$N:")
        println("[bench]   " + percentiles(codecNs, 50.0, 99.0, 99.9))
        assertTrue(codecNs[codecNs.size / 2] < 1_000_000L, "codec p50 should be microseconds, not milliseconds")

        // ── 2. real round trip through the core process ──────────────────────────
        val exe = locateCore()
        assumeTrue(
            exe != null,
            "doppel-core not built — run: cd Doppel/core-rs && cargo build. " +
                "The `core-ipc` CI job builds it and asserts this test really ran."
        )
        val proc = ProcessBuilder(exe!!.absolutePath, "--listen", "tcp:127.0.0.1:0", "--seed", "777333")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val banner = proc.inputStream.bufferedReader().readLine()
            assumeTrue(banner != null && banner.startsWith("LISTEN "), "unexpected banner: $banner")
            val addr = banner!!.removePrefix("LISTEN ").trim()

            CoreStub(Endpoint.parse("tcp:$addr"), null, deadlineNanos = 50_000_000L).use { stub ->
                stub.connect()
                // Warm-up also teaches the JIT the whole path.
                val warm = 2_000
                for (i in 0 until warm) {
                    stub.movementSocd(p, t, true, false, true, false, i)
                }
                assertTrue(stub.timeouts.get() == 0L, "warm-up should not time out")

                val RT = 20_000
                val rtNs = LongArray(RT)
                var answered = 0
                for (i in 0 until RT) {
                    val start = System.nanoTime()
                    val r = stub.movementSocd(p, t, true, false, true, false, i)
                    rtNs[i] = System.nanoTime() - start
                    if (r != null) answered++
                }
                rtNs.sort()
                println("[bench] roundtrip movementSocd via real socket, n=$RT:")
                println("[bench]   " + percentiles(rtNs, 50.0, 99.0, 99.9))
                println("[bench]   answered=$answered/$RT timeouts=${stub.timeouts.get()} roundTrips=${stub.roundTrips.get()}")
                // Loose sanity bound: the 1 ms production deadline is measured by
                // `--bench`; here we only assert the path is not pathologically slow.
                assertTrue(rtNs[(RT * 99 / 100)] < 10_000_000L, "roundtrip p99 under 10 ms")
                assertTrue(answered > RT - RT / 100, "at most 1% of round trips may miss")
            }
            // ── 3. floor: the same socket without the reader-thread hand-off ──────
            // One write plus one blocking read on the calling thread, no latch,
            // no pending map, no second thread. This is the transport floor the
            // stub's dispatch design sits on top of: the gap between this number
            // and section 2's is the cost of the hand-off machinery itself.
            java.net.Socket().use { raw ->
                raw.tcpNoDelay = true
                raw.connect(java.net.InetSocketAddress("127.0.0.1", Endpoint.parse("tcp:$addr").let { (it as Endpoint.Tcp).port }), 500)
                val rin = raw.getInputStream()
                val rout = raw.getOutputStream()
                val wbuf = ByteArray(Wire.FRAME)
                val rbuf = ByteArray(Wire.FRAME)

                fun rawFrame(kind: Int, seq: Int, module: Int, payloadLen: Int) {
                    java.util.Arrays.fill(wbuf, 0)
                    val b = java.nio.ByteBuffer.wrap(wbuf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.putInt(Wire.MAGIC); b.putShort(Wire.VERSION.toShort()); b.putShort(kind.toShort())
                    b.putInt(seq); b.putInt(0); b.putShort(payloadLen.toShort()); b.putShort(0)
                    b.put(module.toByte()); b.put(0); b.putShort(0); b.putLong(0L)
                    rout.write(wbuf); rout.flush()
                }
                fun readFrame() {
                    var f = 0
                    while (f < rbuf.size) {
                        val n = rin.read(rbuf, f, rbuf.size - f)
                        if (n < 0) throw java.io.IOException("eof")
                        f += n
                    }
                }

                rawFrame(Wire.KIND_HELLO, 1, Wire.MODULE_VELOCITY, 0)
                readFrame() // HelloAck
                for (i in 0 until 2000) { rawFrame(Wire.KIND_SNAPSHOT, i + 2, Wire.MODULE_MOVEMENT, 266); readFrame() }
                val RN = 20_000
                val rawNs = LongArray(RN)
                for (i in 0 until RN) {
                    val start = System.nanoTime()
                    rawFrame(Wire.KIND_SNAPSHOT, i + 2, Wire.MODULE_MOVEMENT, 266)
                    readFrame()
                    rawNs[i] = System.nanoTime() - start
                }
                rawNs.sort()
                println("[bench] floor raw write+readFrame (no latch, no reader thread), n=$RN:")
                println("[bench]   " + percentiles(rawNs, 50.0, 99.0, 99.9))
                assertTrue(rawNs[rawNs.size / 2] < 1_000_000L, "raw floor p50 should be microseconds")
            }
        } finally {
            proc.destroyForcibly()
        }
    }
}
