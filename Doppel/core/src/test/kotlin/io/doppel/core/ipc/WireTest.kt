package io.doppel.core.ipc

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.model.VelocityContext
import io.doppel.core.strategy.click.WeaponType
import io.doppel.core.util.Vec2
import io.doppel.core.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Byte-layout tests. The offsets here are the same table as IPC-PROTOCOL.md and
 * `session::decode_snapshot`; a drift in any of them shows up as a wrong number
 * on the other process, days later, in a replay.
 */
class WireTest {

    private fun player() = PlayerState(
        name = "me",
        position = Vec3(1.5, 64.0, -2.5),
        rotation = Vec2(90f, 12f),
        motionX = 0.1, motionY = -0.08, motionZ = 0.2,
        onGround = true,
        isMoving = true,
        isMovingForward = true,
        isSprinting = true,
        health = 18.5f,
        hurtTime = 3,
        maxHurtResistantTime = 10,
        hurtResistantTime = 7,
        isBlocking = false,
        isUsingItem = false,
        isLookingAtTarget = true,
        isMining = false,
        isSneaking = false,
        selectedSlot = 2,
        weaponType = WeaponType.SWORD,
        isAttackKeyDown = true,
        ticks = 12345L
    )

    private fun target() = TargetState(
        entityId = 9,
        name = "them",
        posX = 1.5, posY = 64.0, posZ = 1.0,
        motionX = 0.0, motionY = 0.0, motionZ = -0.1,
        health = 14f, hurtTime = 4,
        distance = 3.5f,
        hitbox = io.doppel.core.model.Hitbox(1.2, 63.2, 0.7, 1.8, 65.0, 1.3)
    )

    @Test
    fun `snapshot is exactly one frame slot`() {
        val ctx = VelocityContext(Vec3(0.4, 0.36, -0.3), player(), target(), Unit)
        assertEquals(Wire.SLOT_SNAPSHOT, encodeSnapshot(ctx).size)
    }

    @Test
    fun `snapshot without target is the same size`() {
        val ctx = VelocityContext(Vec3(0.4, 0.36, -0.3), player(), null, Unit)
        assertEquals(Wire.SLOT_SNAPSHOT, encodeSnapshot(ctx).size)
    }

    @Test
    fun `frame roundtrips through encode and decode`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val f = Frame(Wire.KIND_SNAPSHOT, 77, 4242, payload = payload.copyOf(Wire.MAX_PAYLOAD), payloadLen = payload.size)
        val bytes = f.encode()
        assertEquals(Wire.FRAME, bytes.size)

        val back = Frame.decode(bytes)
        assertNotNull(back)
        assertEquals(Wire.KIND_SNAPSHOT, back!!.kind)
        assertEquals(77, back.seq)
        assertEquals(4242, back.tick)
        assertEquals(4, back.payloadLen)
        assertEquals(3, back.payload[2].toInt())
    }

    @Test
    fun `decode rejects a wrong magic`() {
        val bytes = ByteArray(Wire.FRAME)
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun `decode rejects a truncated buffer`() {
        assertNull(Frame.decode(ByteArray(100)))
    }

    @Test
    fun `decision decodes back to the same numbers`() {
        val f = Frame(
            Wire.KIND_DECISION, 5, 99,
            payload = ByteArray(Wire.MAX_PAYLOAD), payloadLen = Wire.SLOT_DECISION
        )
        val b = java.nio.ByteBuffer.wrap(f.payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(Wire.ACTION_MODIFY.toByte())
        b.put(4)
        b.putShort(0)
        b.putDouble(0.21)
        b.putDouble(0.18)
        b.putDouble(-0.15)
        b.putInt(0)
        b.putInt(0)
        b.putInt(99)

        val d = decodeDecision(f)
        assertEquals(Wire.ACTION_MODIFY, d.action)
        assertEquals(4, d.reason)
        assertEquals(0.21, d.motion.x, 0.0)
        assertEquals(99, d.tick)
    }

    @Test
    fun `packed flags survive the trip`() {
        val ctx = VelocityContext(Vec3(1.0, 0.0, 0.0), player(), null, Unit)
        val bytes = encodeSnapshot(ctx)
        // flags are bytes 2..3, little-endian
        val flags = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        assertTrue(flags and (1 shl 0) != 0, "onGround")
        assertTrue(flags and (1 shl 1) != 0, "isMoving")
        assertTrue(flags and (1 shl 3) != 0, "isSprinting")
        assertTrue(flags and (1 shl 4) != 0, "isLookingAtTarget")
        assertTrue(flags and (1 shl 8) != 0, "isAttackKeyDown")
        assertTrue(flags and (1 shl 7) == 0, "isSneaking should be clear")
    }
}
