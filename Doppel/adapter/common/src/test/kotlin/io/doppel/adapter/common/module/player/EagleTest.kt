package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.contract.model.PlayerState
import io.doppel.ipc.CoreLink
import io.doppel.ipc.CoreStub
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Eagle's wiring — the part that is still in this process.
 *
 * The machine itself now lives in `core-rs` (`strategy::eagle`); its arithmetic
 * is matched tick-for-tick against the Kotlin oracle in `EagleParityTest`. What
 * is left here is everything the core cannot do: turning an answer into a key
 * write, the HUD flash, the manual-sneak guard, and what happens when the core
 * does not answer at all.
 *
 * The stand-in below reproduces the same state machine, so the hold-delay
 * behaviour is still exercised end to end. It is a deliberate re-implementation
 * rather than the oracle: if the two ever drift, the golden fixture is what
 * notices.
 */
class EagleTest {

    private lateinit var core: CoreStub

    private var onEdge = false
    private var presses = 0
    private var releases = 0

    /** Core-side memory, mirrored so the mock can answer like the real thing. */
    private var coreOwns = false
    private var coreDelay = 0

    /**
     * `strategy::eagle::tick`, packed the way the wire packs it:
     * bit0 press, bit1 release, bit2 "the core owns the sneak".
     */
    private fun step(onEdge: Boolean, settingsMet: Boolean, sneakHeld: Boolean): Int {
        if (!settingsMet) {
            val release = coreOwns && !sneakHeld
            coreOwns = false
            coreDelay = 0
            return if (release) 2 else 0
        }
        if (onEdge) {
            if (coreOwns) return 4
            if (sneakHeld) return 0
            coreOwns = true
            coreDelay = 1 // MaxSneakTimeMin == MaxSneakTimeMax == 1 in these tests
            return 1 or 4
        }
        if (!coreOwns) return 0
        if (coreDelay > 0) {
            coreDelay--
            return 4
        }
        if (sneakHeld) return 4
        coreOwns = false
        return 2
    }

    @BeforeEach
    fun setUp() {
        EventBridge.reset()
        presses = 0
        releases = 0
        onEdge = false
        coreOwns = false
        coreDelay = 0

        core = mockk(relaxed = true)
        every {
            core.playerEagle(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            step(arg(2), arg(3), arg(4))
        }
        CoreLink.stub = core

        EventBridge.registerEdgeDetector { onEdge }
        EventBridge.registerPressSneakHandler { presses++ }
        EventBridge.registerReleaseSneakHandler { releases++ }
        // Pin the hold delay so the module tests are deterministic (defaults are 1..5 random ticks).
        ConfigManager.set("Eagle.MaxSneakTimeMin", 1)
        ConfigManager.set("Eagle.MaxSneakTimeMax", 1)
        ConfigManager.set("Eagle.OnlyWhenLookingDown", false)
        ConfigManager.set("Eagle.LookDownThreshold", 45)
        Eagle.enable()
    }

    @AfterEach
    fun tearDown() {
        Eagle.disable()
        CoreLink.stub = null
        EventBridge.reset()
        // The option store is process-global: put Eagle's declared defaults back for other tests.
        ConfigManager.set("Eagle.MaxSneakTimeMin", 1)
        ConfigManager.set("Eagle.MaxSneakTimeMax", 5)
        ConfigManager.set("Eagle.OnlyWhenLookingDown", false)
        ConfigManager.set("Eagle.LookDownThreshold", 45)
    }

    private fun player(pitch: Float = 0f, sneaking: Boolean = false): PlayerState =
        PlayerState(
            "tester", 0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, pitch,
            true, false, false, 20f, 0, 1L
        ).copy(isSneaking = sneaking)

    @Test
    fun `on the edge the module presses sneak and flashes the HUD`() {
        onEdge = true
        EventBridge.onTick(player(), null)

        assertEquals(1, presses)
        assertEquals(0, releases)
        assertTrue(Eagle.flashStrength() > 0f, "the HUD row flashes while the module is working")
    }

    @Test
    fun `leaving the edge releases the sneak one hold-tick later`() {
        onEdge = true
        EventBridge.onTick(player(), null)
        assertEquals(1, presses)

        onEdge = false
        EventBridge.onTick(player(), null) // hold tick (delay 1)
        assertEquals(0, releases, "the hold has not elapsed yet")

        EventBridge.onTick(player(), null) // delay spent -> release
        assertEquals(1, releases)
    }

    @Test
    fun `disabling the module gives the sneak key back`() {
        onEdge = true
        EventBridge.onTick(player(), null)
        assertEquals(1, presses)

        Eagle.disable()
        assertEquals(1, releases, "a disabled Eagle must not leave the player stuck sneaking")
    }

    @Test
    fun `a manual sneak is never pressed over nor released`() {
        onEdge = true
        EventBridge.onTick(player(sneaking = true), null)
        assertEquals(0, presses, "the player is already sneaking")

        Eagle.disable()
        assertEquals(0, releases, "the module never owned that key")
    }

    @Test
    fun `the pitch gate keeps the sneak key up when the player looks forward`() {
        ConfigManager.set("Eagle.OnlyWhenLookingDown", true)
        onEdge = true

        EventBridge.onTick(player(pitch = 0f), null)
        assertEquals(0, presses, "looking straight ahead fails the gate")

        EventBridge.onTick(player(pitch = 60f), null)
        assertEquals(1, presses, "looking down past the threshold engages")
    }

    @Test
    fun `a missing answer writes no key state at all`() {
        // The core is gone mid-session. Pressing would arm a sneak nobody owns;
        // releasing would cancel one the player is holding by hand. Doing nothing
        // is the only safe direction, even though it costs the hold.
        every {
            core.playerEagle(any(), any(), any(), any(), any(), any(), any(), any())
        } returns null

        onEdge = true
        EventBridge.onTick(player(), null)

        assertEquals(0, presses, "no press without an answer")
        assertEquals(0, releases, "no release without an answer")

        Eagle.disable()
        assertEquals(0, releases, "and nothing to release on disable either")
    }

    @Test
    fun `enabling and disabling both forget the core's sneak state`() {
        // A hold that survives a disable would be released later by a module that
        // no longer owns it — the player's own sneak is the thing that gets hit.
        var resets = 0
        every { core.reset(any()) } answers { resets++ }

        Eagle.disable()
        Eagle.enable()
        assertTrue(resets >= 2, "both transitions must reset the player module, saw $resets")
    }
}
