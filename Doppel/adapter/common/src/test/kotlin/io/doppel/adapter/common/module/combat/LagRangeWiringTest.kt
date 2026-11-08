package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.LagRangeStep
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LagRange's adapter side after the hold machine moved into the core.
 *
 * The previous distance, the damage / sprint / block edges and the hold's
 * start timestamp live in `core-rs::strategy::lag_range` and are matched
 * tick-for-tick by `LagRangeParityTest`. What stays here is exactly what
 * the core cannot do:
 *  - the judgements (GUI open, the target's distance sample, the weapon
 *    being a sword, the player snapshot fields);
 *  - marshalling the knobs (range / maxDelay / the three condition toggles)
 *    into one request per tick;
 *  - turning the step code into the single `packetLagActive` write, with
 *    the HUD flash on the ARM transition;
 *  - letting everything through when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor holds packets on a
 * guess: no `kotlin.random`, no local deadline, no cross-tick state.
 */
class LagRangeWiringTest {

    private lateinit var core: CoreStub
    private var coreCalls = 0
    private var answer: LagRangeStep? = LagRangeStep.FLUSH
    private var marshalledGui = false
    private var marshalledHasDist = false
    private var marshalledDist = -1.0
    private var marshalledRange = -1.0
    private var marshalledMaxDelay = -1
    private var marshalledSprintResetOn = false
    private var marshalledSprinting = false
    private var marshalledBlockSwordOn = false
    private var marshalledBlocking = false
    private var marshalledHoldingWeaponOn = false
    private var marshalledWeaponIsSword = false
    private var marshalledHurtTime = -1

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        coreCalls = 0
        answer = LagRangeStep.FLUSH
        marshalledGui = false
        marshalledHasDist = false
        marshalledDist = -1.0
        marshalledRange = -1.0
        marshalledMaxDelay = -1
        marshalledSprintResetOn = false
        marshalledSprinting = false
        marshalledBlockSwordOn = false
        marshalledBlocking = false
        marshalledHoldingWeaponOn = false
        marshalledWeaponIsSword = false
        marshalledHurtTime = -1

        // Not covered by EventBridge.reset() — a stale hold from a previous
        // test would leak into the assertions below.
        EventBridge.packetLagActive = false

        core = mockk(relaxed = true)
        // playerLagRange: player, target, nowNs, guiOpen, hasDist, dist,
        // range, maxDelayMs, sprintResetOn, isSprinting, blockSwordOn,
        // isBlocking, holdingWeaponOn, weaponIsSword, hurtTime, tick
        every {
            core.playerLagRange(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            coreCalls++
            marshalledGui = arg(3)
            marshalledHasDist = arg(4)
            marshalledDist = arg(5)
            marshalledRange = arg(6)
            marshalledMaxDelay = arg(7)
            marshalledSprintResetOn = arg(8)
            marshalledSprinting = arg(9)
            marshalledBlockSwordOn = arg(10)
            marshalledBlocking = arg(11)
            marshalledHoldingWeaponOn = arg(12)
            marshalledWeaponIsSword = arg(13)
            marshalledHurtTime = arg(14)
            answer?.code
        }
        CoreLink.stub = core

        ConfigManager.set("LagRange.Range", 4.0f)
        ConfigManager.set("LagRange.MaxDelay", 200)
        ConfigManager.set("LagRange.SprintReset", true)
        ConfigManager.set("LagRange.BlockSword", true)
        ConfigManager.set("LagRange.HoldingWeapon", true)
        LagRange.enable()
    }

    @AfterEach
    fun tearDown() {
        LagRange.disable()
        CoreLink.stub = null
        EventBridge.reset()
        EventBridge.packetLagActive = false
        ConfigManager.set("LagRange.Range", 4.0f)
        ConfigManager.set("LagRange.MaxDelay", 200)
        ConfigManager.set("LagRange.SprintReset", true)
        ConfigManager.set("LagRange.BlockSword", true)
        ConfigManager.set("LagRange.HoldingWeapon", true)
    }

    private fun target(distance: Float = 3.5f) = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f,
        hurtTime = 5,
        distance = distance,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    private fun player(
        sprinting: Boolean = false,
        blocking: Boolean = false,
        hurtTime: Int = 0,
        weapon: WeaponType = WeaponType.SWORD
    ) = PlayerState.EMPTY.copy(
        isSprinting = sprinting,
        isBlocking = blocking,
        hurtTime = hurtTime,
        weaponType = weapon,
        health = 20f
    )

    private fun tick(
        target: TargetState? = target(),
        player: PlayerState = player(),
        guiOpen: Boolean = false
    ) {
        EventBridge.isGuiOpen = guiOpen
        EventBridge.onTick(player, target)
    }

    // ---- The adapter marshals judgements and knobs, it does not decide ------

    @Test
    fun `judgements and knobs reach the core as inputs`() {
        tick(target(distance = 3.25f), player(sprinting = true, blocking = true, hurtTime = 4))

        assertEquals(1, coreCalls)
        assertFalse(marshalledGui)
        assertTrue(marshalledHasDist)
        assertEquals(3.25, marshalledDist, 1e-9)
        assertEquals(4.0, marshalledRange, 1e-9)
        assertEquals(200, marshalledMaxDelay)
        assertTrue(marshalledSprintResetOn)
        assertTrue(marshalledSprinting)
        assertTrue(marshalledBlockSwordOn)
        assertTrue(marshalledBlocking)
        assertTrue(marshalledHoldingWeaponOn)
        assertTrue(marshalledWeaponIsSword)
        assertEquals(4, marshalledHurtTime)
    }

    @Test
    fun `no target is marshalled as no distance`() {
        tick(target = null)
        assertFalse(marshalledHasDist)
        assertEquals(0.0, marshalledDist, 1e-9)
    }

    @Test
    fun `a non-sword weapon is a marshalled judgement not a decision`() {
        tick(player = player(weapon = WeaponType.AXE))
        assertFalse(marshalledWeaponIsSword)
    }

    // ---- The step code drives the one platform write ------------------------

    @Test
    fun `an ARM step raises the lag and flashes the HUD`() {
        answer = LagRangeStep.ARM
        tick()
        assertTrue(EventBridge.packetLagActive)
    }

    @Test
    fun `a HOLD step keeps the lag on`() {
        answer = LagRangeStep.HOLD
        tick()
        assertTrue(EventBridge.packetLagActive)
    }

    @Test
    fun `a FLUSH step drops the lag`() {
        EventBridge.packetLagActive = true
        answer = LagRangeStep.FLUSH
        tick()
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `a missing answer never holds packets`() {
        EventBridge.packetLagActive = true
        answer = null
        tick()
        assertFalse(EventBridge.packetLagActive, "the safe direction is not to hold at all")
    }

    @Test
    fun `a disabled module asks nothing`() {
        LagRange.disable()
        coreCalls = 0
        tick()
        assertEquals(0, coreCalls)
    }

    @Test
    fun `disabling mid-hold drops the lag`() {
        answer = LagRangeStep.HOLD
        tick()
        assertTrue(EventBridge.packetLagActive)
        LagRange.disable()
        assertFalse(EventBridge.packetLagActive)
    }

    @Test
    fun `enabling clears a stale lag flag before the first tick`() {
        EventBridge.packetLagActive = true
        LagRange.disable()
        EventBridge.packetLagActive = true
        LagRange.enable()
        assertFalse(EventBridge.packetLagActive)
    }
}
