package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.model.Hitbox
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * KeepSprint — the sprint STATE, not the sprint SPEED.
 *
 * The module used to undo vanilla's post-hit `motion *= 0.6` (Raven's `Slow %`). That entire path is
 * deleted — see the module doc for why it cannot be made safe — and these tests pin what replaced
 * it: the sprint flag survives a landed swing and the server is told about it, because MC-69459
 * leaves the server believing the player is walking from the second hit onward. Nothing here writes
 * motion.
 *
 * The timing tests exist because both halves of that are easy to get subtly wrong: detection has to
 * be per tick (a held button attacks every tick in 1.8), and the local hold has to outlive a tick
 * boundary (the render frame runs 3-5 times per tick).
 */
class KeepSprintTest {

    private val sent = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        EventBridge.registerEntityActionHandler { sent.add(it) }
        sent.clear()
        EventBridge.crosshairTarget = null
        EventBridge.isLeftMousePhysicallyDown = false
        EventBridge.syntheticAttack = false
        // The module only preserves a sprint the player already had; the snapshot is normally
        // taken in onStartTick. Tests drive it directly.
        EventBridge.localSprinting = true
        // A sprint implies forward input, and the hold ends the moment the player stops pushing.
        EventBridge.isKeyForwardDown = true
    }

    @AfterEach
    fun tearDown() {
        KeepSprint.disable()
        EventBridge.crosshairTarget = null
        EventBridge.isLeftMousePhysicallyDown = false
        EventBridge.syntheticAttack = false
        EventBridge.localSprinting = false
        EventBridge.isKeyForwardDown = false
        EventBridge.reset()
        sent.clear()
    }

    private fun target(health: Float = 20f) = TargetState(
        entityId = 7,
        name = "dummy",
        posX = 1.0, posY = 0.0, posZ = 0.0,
        motionX = 0.0, motionY = 0.0, motionZ = 0.0,
        health = health,
        hurtTime = 0,
        distance = 1.5f,
        hitbox = Hitbox(0.0, 0.0, 0.0, 0.6, 1.8, 0.6)
    )

    /** One render frame with the given inputs. */
    private fun frame(attacking: Boolean = false, target: TargetState? = null) {
        EventBridge.crosshairTarget = target
        EventBridge.isLeftMousePhysicallyDown = attacking
        KeepSprint.onRenderFrame(Unit)
    }

    /** Advance one client tick without touching the sprint snapshot. */
    private fun tick() {
        EventBridge.onTick(PlayerState.EMPTY, null)
    }

    @Test
    fun `a landed swing tells a server that thinks we stopped sprinting`() {
        KeepSprint.enable()
        // MC-69459: the last landed hit is what clears the server's copy.
        EventBridge.reportServerSprintState(false)

        frame(attacking = true, target = target())

        assertEquals(listOf("START_SPRINTING"), sent)
    }

    @Test
    fun `the mirror reading sprinting is not a reason to stay silent`() {
        // The regression this replaced asserted the opposite. serverSprintState is reported from the
        // platform's sprint setter, so it tracks the LOCAL flag — and in MC-69459 the local flag is
        // still true while the server holds false. Trusting the mirror means trusting our own
        // optimism and never sending the one packet that resyncs it.
        KeepSprint.enable()
        EventBridge.reportServerSprintState(true)

        frame(attacking = true, target = target())

        assertEquals(listOf("START_SPRINTING"), sent)
    }

    @Test
    fun `a swing at nothing asserts nothing`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)

        frame(attacking = true, target = null)

        assertTrue(sent.isEmpty(), "vanilla only clears the flag for a hit that landed")
    }

    @Test
    fun `a swing at a dead target asserts nothing`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)

        frame(attacking = true, target = target(health = 0f))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a player who is not sprinting gets no sprint forced on them`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)
        EventBridge.localSprinting = false
        KeepSprint.reset() // drop the latch left by the previous frames

        frame(attacking = true, target = target())

        assertTrue(sent.isEmpty(), "there was no sprint to keep")
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
    }

    @Test
    fun `a sample taken after the hit still counts as sprinting`() {
        // Forge drives the whole dispatch from a 20 Hz background thread, so the sample can land
        // after vanilla cleared the flag. The module latches instead of trusting one read.
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)

        frame(attacking = false)                 // this frame sees localSprinting = true
        EventBridge.localSprinting = false       // next sample lands post-attack
        frame(attacking = true, target = target())

        assertEquals(listOf("START_SPRINTING"), sent)
    }

    @Test
    fun `the hold ends when the player stops pushing forward`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))

        // Releasing W is the player ending the sprint themselves — a preserved flag would render
        // sprint particles for someone who is standing still.
        EventBridge.isKeyForwardDown = false
        frame()

        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
    }

    @Test
    fun `holding the button sends one packet per tick, not one per frame`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)
        val dummy = target()

        repeat(10) { frame(attacking = true, target = dummy) }
        assertEquals(1, sent.size, "10 frames inside one tick are still one attack")

        // 1.8 polls the attack key every tick, so a held button really is a new attack next tick —
        // and the server really does clear its flag again. An edge detector would miss this.
        tick()
        frame(attacking = true, target = dummy)
        assertEquals(2, sent.size)
    }

    @Test
    fun `the local hold is released after a tick, not left claimed`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)

        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))

        tick()
        frame()
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name), "must survive a tick boundary")

        tick()
        frame()
        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name), "no opinion again — vanilla decides")
    }

    @Test
    fun `disabling mid-hold gives the claim back`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)
        frame(attacking = true, target = target())
        assertTrue(EventBridge.hasSprintClaim(KeepSprint.name))

        KeepSprint.disable()

        assertFalse(EventBridge.hasSprintClaim(KeepSprint.name))
    }

    @Test
    fun `the numeric knobs are gone`() {
        // Not defaulted off — removed. No configuration can make this module move the client faster
        // than the server's own post-hit model, because that damp is a bilateral rule.
        assertNull(ConfigManager.getMeta("KeepSprint.Slow"))
        assertNull(ConfigManager.getMeta("KeepSprint.StopSprint"))
        assertNull(ConfigManager.getMeta("KeepSprint.DisableWhileJump"))
        assertNull(ConfigManager.getMeta("KeepSprint.OnlyReduceReachHits"))
    }
}
