package io.doppel.adapter.common.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module

/**
 * A2 — the synthetic key fields are *arbitrated*, not written by whoever happens to be last.
 *
 * Reported conflict: WTap owns W (a W-tap **is** a W release) and the Velocity Sprint brace asks for
 * W pressed (press toward the knockback source) — in a trade both fire on the same tick. Before
 * arbitration they wrote `syntheticForward` directly, so the loser's intent vanished and the winner
 * depended on tick order; `releaseBraceKeys()` additionally cleared ADTap's A/D as a side effect.
 *
 * These are the rules the adapters and the Keystrokes contract now depend on. They are the
 * counterpart to [EventBridgeEffectiveKeysTest], which pins the override semantics for the HUD.
 *
 * Convention: a claim source is the module's own `Module.name` — that is what makes the
 * [Module.disable] safety net work without a per-module registry.
 */
class EventBridgeKeyArbitrationTest {

    private val brace = "Velocity"
    private val wtap = "WTap"
    private val stap = "STap"
    private val adtap = "ADTap"

    /** Stand-in for any module; only its name and lifecycle matter to the arbiter. */
    private object ArbTestModule : Module("ArbTestModule", Category.COMBAT)

    @BeforeEach
    fun setUp() = EventBridge.reset()

    @AfterEach
    fun tearDown() = EventBridge.reset()

    @Test
    fun `an owned claim beats an additive claim in the same tick`() {
        EventBridge.isKeyForwardDown = true          // player is holding W
        EventBridge.claimForward(brace, true)         // brace: press W toward the source
        EventBridge.claimForward(
            wtap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
        )                                             // W-tap: release W

        assertFalse(EventBridge.isKeyForwardActive, "the tap must win the shared tick")
    }

    @Test
    fun `the additive claim resumes as soon as the owned claim is released`() {
        EventBridge.isKeyForwardDown = true
        EventBridge.claimForward(brace, true)
        EventBridge.claimForward(
            wtap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
        )
        assertFalse(EventBridge.isKeyForwardActive)

        EventBridge.releaseForward(wtap)              // the tap ended one tick later

        assertTrue(EventBridge.isKeyForwardActive, "the brace must resume, not stay cancelled")
    }

    @Test
    fun `an additive claim ORs with the physical key`() {
        EventBridge.isKeyForwardDown = true           // player holds W, nobody claims
        assertTrue(EventBridge.isKeyForwardActive)

        EventBridge.claimForward(brace, false)        // brace wants W up this tick
        assertTrue(EventBridge.isKeyForwardActive, "additive never steals the player's key")
    }

    @Test
    fun `releasing one source does not clear another source's key`() {
        EventBridge.claimForward(brace, true)
        EventBridge.claimLeft(brace, true)
        EventBridge.claimLeft(adtap, true)            // ADTap also wants A, different reason
        EventBridge.claimRight(adtap, true)

        EventBridge.releaseForward(brace)
        EventBridge.releaseLeft(brace)
        EventBridge.releaseRight(brace)               // brace's release used to wipe ALL four

        assertFalse(EventBridge.isKeyForwardActive, "brace's own forward claim is gone")
        assertTrue(EventBridge.isKeyLeftActive, "ADTap's A must survive the brace's release")
        assertTrue(EventBridge.isKeyRightActive, "ADTap's D must survive the brace's release")
    }

    @Test
    fun `claims on different keys do not interfere`() {
        EventBridge.claimForward(
            wtap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
        )
        EventBridge.claimBack(
            stap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
        )

        assertFalse(EventBridge.isKeyForwardActive)
        assertFalse(EventBridge.isKeyBackActive)
        assertFalse(EventBridge.isKeyLeftActive)
        assertFalse(EventBridge.isKeyRightActive)
    }

    @Test
    fun `equal-priority owned claims resolve deterministically, not by write order`() {
        // Two owned claimants at the same priority: the source name breaks the tie, so the result
        // is the same whichever module claimed first.
        EventBridge.claimForward(wtap, true, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP)
        EventBridge.claimForward(stap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP)
        val stapClaimedSecond = EventBridge.isKeyForwardActive

        EventBridge.reset()
        EventBridge.claimForward(stap, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP)
        EventBridge.claimForward(wtap, true, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP)

        assertEquals(stapClaimedSecond, EventBridge.isKeyForwardActive, "order must not matter")
    }

    @Test
    fun `reset drops every claim`() {
        EventBridge.claimForward(brace, true)
        EventBridge.claimLeft(adtap, true)
        assertTrue(EventBridge.isKeyForwardActive)
        assertTrue(EventBridge.isKeyLeftActive)

        // reset() is the shutdown path — it clears the module claims AND the physical mirrors
        EventBridge.reset()

        assertFalse(EventBridge.isKeyForwardActive)
        assertFalse(EventBridge.isKeyLeftActive)
    }

    @Test
    fun `releaseAllClaims drops one source from every key at once`() {
        EventBridge.claimForward(brace, true)
        EventBridge.claimBack(brace, true)
        EventBridge.claimLeft(brace, true)
        EventBridge.claimRight(brace, true)

        EventBridge.releaseAllClaims(brace)

        assertFalse(EventBridge.isKeyForwardActive)
        assertFalse(EventBridge.isKeyBackActive)
        assertFalse(EventBridge.isKeyLeftActive)
        assertFalse(EventBridge.isKeyRightActive)
    }

    @Test
    fun `disabling a module releases every claim it holds`() {
        // The safety net: a module that forgets to release would leave a stale OWNED claim behind,
        // and a stale OWNED claim keeps winning the arbitration *while the module is off*.
        ArbTestModule.enable()
        EventBridge.claimForward(
            ArbTestModule.name, false, EventBridge.KeyClaimMode.OWNED, EventBridge.KEY_PRIORITY_TAP
        )
        EventBridge.claimLeft(ArbTestModule.name, true)
        assertFalse(EventBridge.isKeyForwardActive)
        assertTrue(EventBridge.isKeyLeftActive)

        ArbTestModule.disable()

        assertTrue(EventBridge.isKeyForwardActive, "the owned claim must be gone")
        assertFalse(EventBridge.isKeyLeftActive)
    }

    // ── Sprint flag arbitration ─────────────────────────────────────────────
    // The same ownership problem, one field over: Sprint (movement) asserts sprinting every tick you
    // hold W, which silently ate SuperKnockback's SprintTap — a one-tick forced sprint *off*.

    @Test
    fun `a forced sprint claim beats a want-to-run claim, and hands back afterwards`() {
        val seen = mutableListOf<Boolean>()
        EventBridge.registerSprintSetter { seen.add(it) }

        EventBridge.claimSprint("Sprint", true)                                   // movement
        EventBridge.claimSprint(
            "SuperKnockback", false,
            EventBridge.SprintClaimMode.OWNED, EventBridge.SPRINT_PRIORITY_TAP
        )                                                                         // SprintTap: off
        assertEquals(false, seen.last(), "the one-tick tap must win the shared tick")

        EventBridge.releaseSprint("SuperKnockback")

        assertEquals(true, seen.last(), "and the run claim takes over on the next tick")
    }

    @Test
    fun `releasing the last sprint claim does not assert anything`() {
        // "No claim" is not "force it off": sprint is the game's to drive, and a module that has no
        // opinion must not write the field at all.
        var calls = 0
        EventBridge.registerSprintSetter { calls++ }

        EventBridge.claimSprint("Sprint", true)
        assertEquals(1, calls)

        EventBridge.releaseSprint("Sprint")
        assertEquals(1, calls, "releasing the last claim must leave the game's state alone")
    }
}
