package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.model.Hitbox
import io.doppel.core.model.TargetState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    }

    @AfterEach
    fun tearDown() {
        KeepSprint.disable()
        EventBridge.crosshairTarget = null
        EventBridge.isLeftMousePhysicallyDown = false
        EventBridge.syntheticAttack = false
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

    @Test
    fun `a landed swing tells a server that thinks we stopped sprinting`() {
        KeepSprint.enable()
        // MC-69459: the last landed hit is what clears the server's copy.
        EventBridge.reportServerSprintState(false)

        frame(attacking = true, target = target())

        assertEquals(listOf("START_SPRINTING"), sent)
    }

    @Test
    fun `no packet when the server already believes we are sprinting`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(true)

        frame(attacking = true, target = target())

        assertTrue(sent.isEmpty(), "the mirror is already correct — there is nothing to report")
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
    fun `holding the button sends one packet, not one per frame`() {
        KeepSprint.enable()
        EventBridge.reportServerSprintState(false)
        val dummy = target()

        repeat(10) { frame(attacking = true, target = dummy) }

        assertEquals(1, sent.size, "only the rising edge of the swing counts")
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
