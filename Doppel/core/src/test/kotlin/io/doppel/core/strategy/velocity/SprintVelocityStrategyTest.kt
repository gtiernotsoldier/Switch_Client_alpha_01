package io.doppel.core.strategy.velocity

import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec2
import io.doppel.contract.util.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.doppel.contract.strategy.velocity.VelocityConfig
import io.doppel.contract.strategy.velocity.VelocityMode
import io.doppel.contract.strategy.velocity.VelocityStrategy

/**
 * The SPRINT mode window — the part the adapter polls once per tick.
 *
 * Two things here are load-bearing and easy to break silently:
 *
 *  - the window is a fixed number of TICKS, not "until the next packet", and
 *  - it is published as one immutable object, because `arm` runs on the packet thread and
 *    `braceKeys` on the tick thread. Splitting the direction into two plain fields let a tick read
 *    a new X beside a stale Z and brace 90 degrees off.
 */
class SprintVelocityStrategyTest {

    private val strategy = SprintVelocityStrategy()

    private fun player() = PlayerState(
        name = "me", position = Vec3(0.0, 64.0, 0.0), rotation = Vec2(0f, 0f),
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        onGround = true, isMoving = true, isMovingForward = true,
        isSprinting = true, health = 20f, hurtTime = 0,
        maxHurtResistantTime = 10, isBlocking = false, isUsingItem = false,
        isLookingAtTarget = false, isMining = false, isSneaking = false,
        selectedSlot = 0, weaponType = WeaponType.OTHER,
        isAttackKeyDown = true, ticks = 0
    )

    private fun context(dx: Double, dz: Double) = VelocityContext(
        originalMotion = Vec3(dx, 0.4, dz),
        player = player(),
        target = TargetState(
            entityId = 1, name = "t", posX = 0.0, posY = 64.0, posZ = 3.0,
            motionX = 0.0, motionY = 0.0, motionZ = 0.0, health = 20f, hurtTime = 0,
            distance = 3f, hitbox = Hitbox(-0.3, 63.2, 2.7, 0.3, 65.0, 3.3)
        ),
        packetHandle = Unit
    )

    /** Always passes the gate, so the tests are about the window and not the dice. */
    private fun config(hold: Int = 3) = VelocityConfig(
        mode = VelocityMode.SPRINT,
        sprintHoldTicks = hold,
        sprintGateBraced = 100,
        sprintGateIdle = 100
    )

    private fun never() = VelocityConfig(
        mode = VelocityMode.SPRINT,
        sprintHoldTicks = 3,
        sprintGateBraced = 0,
        sprintGateIdle = 0
    )

    @Test
    fun `the window lasts exactly the configured number of ticks`() {
        val state = VelocityStrategy.State()
        assertTrue(strategy.arm(config(hold = 3), state, context(0.0, -1.0)))

        repeat(3) { assertNotNull(strategy.braceKeys(config(3), state, 0f), "tick ${it + 1}") }
        assertNull(strategy.braceKeys(config(3), state, 0f), "the window is spent")
    }

    @Test
    fun `keys point at the source of the knockback`() {
        val state = VelocityStrategy.State()
        // Pushed toward -Z, so the attacker is at +Z; yaw 0 faces +Z.
        strategy.arm(config(), state, context(0.0, -1.0))
        val keys = strategy.braceKeys(config(), state, 0f)!!
        assertTrue(keys.forward)
        assertFalse(keys.back || keys.left || keys.right)

        // Same hit with the player turned around: the answer must follow the yaw, not the hit.
        val turned = strategy.braceKeys(config(), state, 180f)!!
        assertTrue(turned.back)
        assertFalse(turned.forward || turned.left || turned.right)
    }

    @Test
    fun `a second hit restarts the countdown instead of inheriting the remainder`() {
        val state = VelocityStrategy.State()
        strategy.arm(config(hold = 3), state, context(0.0, -1.0))
        strategy.braceKeys(config(3), state, 0f) // one tick spent
        assertTrue(strategy.arm(config(hold = 3), state, context(0.0, -1.0)))

        repeat(3) { assertNotNull(strategy.braceKeys(config(3), state, 0f), "fresh window, tick ${it + 1}") }
        assertNull(strategy.braceKeys(config(3), state, 0f))
    }

    @Test
    fun `the gate can refuse to open the window`() {
        val state = VelocityStrategy.State()
        assertFalse(strategy.arm(never(), state, context(0.0, -1.0)))
        assertNull(strategy.braceKeys(never(), state, 0f))
    }

    @Test
    fun `a purely vertical hit has no direction to brace against`() {
        val state = VelocityStrategy.State()
        assertFalse(strategy.arm(config(), state, context(0.0, 0.0)))
        assertNull(strategy.braceKeys(config(), state, 0f))
    }

    @Test
    fun `reset closes an open window`() {
        val state = VelocityStrategy.State()
        strategy.arm(config(), state, context(0.0, -1.0))
        state.reset()
        assertNull(strategy.braceKeys(config(), state, 0f))
    }

    @Test
    fun `braceKeys never opens a window on its own`() {
        val state = VelocityStrategy.State()
        assertNull(strategy.braceKeys(config(), state, 45f))
        assertEquals(0, state.braceTicksLeft)
    }
}
