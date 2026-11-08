package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * AutoBlock's adapter side after the two chance rolls moved into the core.
 *
 * The per-tick Chance roll and the Lag window's start roll live in
 * `core-rs::strategy::autoblock_sample` and are matched row-for-row by
 * `AutoBlockSampleParityTest`. What stays here is exactly what the core
 * cannot do:
 *  - the judgements (the mode state machines, the unified condition engine,
 *    the attack/debounce bookkeeping);
 *  - marshalling the configured percentages into one request;
 *  - turning the verdict into the syntheticUse / packetLagActive writes;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor rolls locally: there is
 * no `kotlin.random` left in the module.
 */
class AutoBlockSampleWiringTest {

    private lateinit var core: CoreStub
    private val chanceCalls = mutableListOf<Int>()   // every chance value handed to the core
    private val chanceAnswers = mutableMapOf<Int, Boolean?>()  // per-chance overrides
    private var defaultAnswer: Boolean? = true

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        chanceCalls.clear()
        chanceAnswers.clear()
        defaultAnswer = true
        // Not covered by EventBridge.reset() — a stale window from a previous
        // test would leak into the assertions below.
        EventBridge.packetLagActive = false
        EventBridge.packetLagAttackReleased = false

        core = mockk(relaxed = true)
        // The unified gate and the distance gate answer "open" so the tick
        // reaches the module's own chance roll.
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CombatDecision(true, 0, 1) }
        every {
            core.combatRange(any(), any(), any(), any(), any(), any(), any(), any())
        } answers { true }
        every {
            core.autoblockChance(any(), any(), any(), any())
        } answers {
            val chance = arg<Int>(2)
            chanceCalls.add(chance)
            chanceAnswers[chance] ?: defaultAnswer
        }
        CoreLink.stub = core

        EventBridge.mouseButton0 = true
        EventBridge.crosshairTarget = target()

        ConfigManager.set("AutoBlock.Chance", 77)
    }

    @AfterEach
    fun tearDown() {
        AutoBlock.disable()
        CoreLink.stub = null
        EventBridge.reset()
        EventBridge.packetLagActive = false
        EventBridge.packetLagAttackReleased = false
        ConfigManager.set("AutoBlock.Chance", 100)
        ConfigManager.set("AutoBlock.Mode", "Normal")
        ConfigManager.set("AutoBlock.LagChance", 100)
    }

    private fun target(distance: Float = 2f) = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = 20f,
        hurtTime = 5,
        distance = distance,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /** AutoBlock's sword guard reads the player, so the tick player must hold one. */
    private fun swordPlayer() =
        PlayerState.EMPTY.copy(weaponType = WeaponType.SWORD, health = 20f)

    private fun tick(t: TargetState? = target(), player: PlayerState = swordPlayer()) {
        EventBridge.crosshairTarget = t
        EventBridge.onTick(player, t)
    }

    // ---- Turning the core's verdict into the platform writes ---------------

    @Test
    fun `a passing tick roll engages the block in normal mode`() {
        AutoBlock.enable()
        tick()

        assertTrue(EventBridge.syntheticUse, "the block is pressed on a passing roll")
        assertTrue(chanceCalls.contains(77), "the tick gate asked AutoBlock's own stream")
    }

    @Test
    fun `a failed tick roll never presses the block`() {
        AutoBlock.enable()
        defaultAnswer = false
        tick()

        assertFalse(EventBridge.syntheticUse, "a failed roll does not press the block")
    }

    @Test
    fun `a missing answer degrades to no block`() {
        AutoBlock.enable()
        defaultAnswer = null
        tick()

        assertFalse(EventBridge.syntheticUse, "no core answer means no block, never a local guess")
    }

    @Test
    fun `an open gui spends no roll at all`() {
        AutoBlock.enable()
        EventBridge.isGuiOpen = true
        tick()

        assertTrue(chanceCalls.isEmpty(), "the roll happens only on an armed tick: GUI closed, sword held")
        assertFalse(EventBridge.syntheticUse)
    }

    @Test
    fun `no sword means no roll`() {
        AutoBlock.enable()
        tick(player = PlayerState.EMPTY.copy(weaponType = WeaponType.OTHER, health = 20f))

        assertTrue(chanceCalls.isEmpty(), "the sword guard runs before the roll, exactly as before")
    }

    // ---- The Lag window's start roll ---------------------------------------

    @Test
    fun `the lag window start asks the core and only once per window`() {
        ConfigManager.set("AutoBlock.Mode", "Lag")
        ConfigManager.set("AutoBlock.LagChance", 55)
        AutoBlock.enable()

        tick()   // window start: tick roll (77) + lag roll (55)
        tick()   // window running: tick roll (77) only

        assertTrue(EventBridge.packetLagActive, "a passing lag roll starts the packet window")
        assertEquals(1, chanceCalls.count { it == 55 }, "the lag roll is spent only when a window wants to start")
        assertEquals(2, chanceCalls.count { it == 77 }, "the tick gate rolls once per armed tick")
    }

    @Test
    fun `a failed lag roll starts no window`() {
        ConfigManager.set("AutoBlock.Mode", "Lag")
        ConfigManager.set("AutoBlock.LagChance", 55)
        // The tick gate (77) still passes; only the window's own roll fails.
        chanceAnswers[55] = false
        AutoBlock.enable()
        tick()

        assertFalse(EventBridge.packetLagActive, "the window stays closed on a failed roll")
        assertTrue(chanceCalls.contains(55), "the start roll was still asked for")
    }

    // ---- The configured value is marshalled, not rolled here ---------------

    @Test
    fun `the configured chance reaches the core unchanged`() {
        AutoBlock.enable()
        tick()

        assertEquals(listOf(77), chanceCalls.distinct(), "chance is marshalled verbatim")
    }
}
