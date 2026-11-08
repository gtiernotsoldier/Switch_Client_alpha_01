package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.contract.model.Hitbox
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.ipc.BlockHitSample
import io.doppel.ipc.CombatDecision
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.doppel.ipc.Strategies
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BlockHit's adapter side after the range draws moved into the core.
 *
 * The post-delay and block-duration draws at the fire point, and the
 * hit-threshold draw on enable, live in `core-rs::strategy::blockhit_sample`
 * and are matched row-for-row by `BlockHitSampleParityTest`. What stays here
 * is exactly what the core cannot do:
 *  - the judgements (physical / synthetic click state, target guards, the
 *    sword filter, the CombatTrigger verdict marshalling);
 *  - turning the returned packed scalar into the pressBlock / releaseBlock
 *    state machine transitions;
 *  - doing nothing when the core is silent.
 *
 * These tests prove the adapter neither re-decides nor draws locally: there is
 * no `kotlin.random` left in the module.
 */
class BlockHitSampleWiringTest {

    private lateinit var core: CoreStub
    private val samples = mutableListOf<Pair<Int, List<Int>>>()  // kind to the six configured ranges
    private var fireCode: Double? = BlockHitSample.pack(0, 40, 0).toDouble()
    private var decisionFire = true

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        samples.clear()
        fireCode = BlockHitSample.pack(0, 40, 0).toDouble()
        decisionFire = true

        core = mockk(relaxed = true)
        every {
            core.combatDecide(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers { CombatDecision(decisionFire, 0, 1) }
        every {
            core.blockhitSample(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val kind = arg<Int>(2)
            samples.add(
                kind to listOf(
                    arg<Int>(3), arg<Int>(4), arg<Int>(5),
                    arg<Int>(6), arg<Int>(7), arg<Int>(8)
                )
            )
            if (kind == Strategies.BlockHitSampleKind.FIRE) fireCode else 3.0
        }
        CoreLink.stub = core

        // onRightMBHold default true: the player holds right alongside the
        // attack; the effective left-click comes from the synthetic channel.
        EventBridge.isRightMousePhysicallyDown = true
        EventBridge.syntheticAttack = true
        EventBridge.crosshairTarget = target()
    }

    @AfterEach
    fun tearDown() {
        BlockHit.disable()
        CoreLink.stub = null
        EventBridge.reset()
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

    /** onlySword default true: the tick player must hold a sword. */
    private fun swordPlayer() =
        PlayerState.EMPTY.copy(weaponType = WeaponType.SWORD, health = 20f)

    private fun tick(t: TargetState? = target(), player: PlayerState = swordPlayer()) {
        EventBridge.crosshairTarget = t
        EventBridge.onTick(player, t)
    }

    // ---- Turning the core's packed answer into the platform writes ---------

    @Test
    fun `a fire with zero post-delay presses immediately with the core duration`() {
        BlockHit.enable()
        tick()

        assertTrue(EventBridge.syntheticUse, "post-delay 0 → the block presses this tick")
        assertTrue(
            samples.any { it.first == Strategies.BlockHitSampleKind.FIRE },
            "the fire point asks BlockHit's own stream for both draws"
        )
    }

    @Test
    fun `a fire with a post-delay arms the delay instead of pressing`() {
        fireCode = BlockHitSample.pack(50, 40, 0).toDouble()
        BlockHit.enable()
        tick()

        assertFalse(
            EventBridge.syntheticUse,
            "a non-zero post-delay waits; the press lands on a later tick"
        )
        assertTrue(samples.any { it.first == Strategies.BlockHitSampleKind.FIRE })
    }

    @Test
    fun `a missing fire answer degrades to no block-hit`() {
        fireCode = null
        BlockHit.enable()
        tick()

        assertFalse(EventBridge.syntheticUse, "no core answer means no press, never a local guess")
    }

    @Test
    fun `a trigger that does not fire spends no draw`() {
        decisionFire = false
        BlockHit.enable()
        tick()

        assertTrue(
            samples.none { it.first == Strategies.BlockHitSampleKind.FIRE },
            "the draws happen only at the fire point"
        )
        assertFalse(EventBridge.syntheticUse)
    }

    @Test
    fun `the right-click guard runs before any draw`() {
        EventBridge.isRightMousePhysicallyDown = false
        EventBridge.syntheticUse = false
        BlockHit.enable()
        tick()

        // The enable-time INIT draw is legitimate; a closed guard must stop
        // everything the tick itself would have spent.
        assertTrue(
            samples.none { it.first == Strategies.BlockHitSampleKind.FIRE },
            "no fire draw may be spent while the guard is closed"
        )
    }

    // ---- The configured values are marshalled, not sampled here ------------

    @Test
    fun `enable seeds the threshold from the core`() {
        BlockHit.enable()

        val init = samples.last { it.first == Strategies.BlockHitSampleKind.INIT }
        assertEquals(
            listOf(10, 40, 110, 150, 1, 1),
            init.second,
            "INIT marshals the post-delay / wait / hitPer pairs verbatim"
        )
    }

    @Test
    fun `the fire point marshals all three range pairs unchanged`() {
        BlockHit.enable()
        tick()

        val fire = samples.last { it.first == Strategies.BlockHitSampleKind.FIRE }
        assertEquals(
            listOf(10, 40, 110, 150, 1, 1),
            fire.second,
            "postDelayMin/Max, waitMin/Max, hitPerMin/Max are marshalled verbatim"
        )
    }
}
