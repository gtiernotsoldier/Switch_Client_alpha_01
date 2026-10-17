package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.api.EventBridge

/**
 * KeepSprint — carry the sprint state across a hit.
 *
 * Vanilla 1.8.9 ends a successful attack with (EntityPlayer.attackTargetEntityWithCurrentItem):
 *
 *     this.motionX *= 0.6D;
 *     this.motionZ *= 0.6D;
 *     this.setSprinting(false);
 *
 * This module deals with the third line only.
 *
 * ── Why the motion damp is not touched
 *
 * Earlier revisions also undid the 0.6 (Raven's `Slow %`, default 40 == vanilla). That path is
 * deleted outright rather than defaulted off, because it cannot be made safe:
 *
 *  - **the damp is bilateral.** The server runs the same line on its own entity, so a client that
 *    skips it is persistently faster than the server's model of it — not a one-frame spike, a
 *    standing disagreement.
 *  - **vanilla hands the speed back anyway, quickly.** The 0.6 removes `0.4 x V`, about 0.11
 *    blocks/tick at sprint speed, while ground sprint acceleration alone is 0.13 per tick. Recovery
 *    is a few ticks, not a fight. What was being bought with a detectable signature was a handful
 *    of ticks.
 *  - **this platform has no injection into the attack method**, so a correction could only run on
 *    the render frame *after* the tick that applied the damp. The server would see `0.6V` and then
 *    `V` — a visible jump — and the position packet for that window may already have gone out
 *    carrying the damped value.
 *
 * ── What it does instead
 *
 * The sprint FLAG is a different matter: changing it produces no numeric difference whatsoever, and
 * keeping it is worth real knockback — a sprinting attacker's knockback is roughly 2.25x, and the
 * sprint movement bonus is 1.3x instead of 1.0x.
 *
 * It also closes a vanilla bug. **MC-69459**: when you attack while holding sprint, the client
 * clears the flag and re-asserts it from the held key *inside the same tick*, so the client's own
 * flag never appears to change and it therefore never sends the C0B. The client thinks you are
 * sprinting and the server thinks you are not — and it is the server's copy that decides whether
 * the next hit counts as a sprint hit. From the second hit onward the server only ever pays walking
 * knockback. This module tells it.
 *
 * ⚠ Interaction with the rest of the family:
 *  - `SprintReset` sends STOP_SPRINTING **and** START_SPRINTING. This module deliberately does NOT
 *    stop first: the stop/start pair gives the server a one-tick window where it believes you are
 *    walking, which is the exact state this module exists to avoid. Both end with the server
 *    believing you are sprinting, so if the sprint *hit* is all you want, this single packet is the
 *    smoother of the two.
 *  - `WTap` resets the sprint *on purpose* by releasing W. That is the opposite intent, so this
 *    module stands down while a tap owns the W key (see the guard in [onRenderFrame]) — the tap is
 *    deliberate, the preserve is automatic. `STap` sets sprint off by pressing S instead and owns a
 *    different key, so the two still disagree on the sprint state: pick one.
 *  - `Sprint` (movement) already re-asserts sprint every tick you hold forward, so the local half
 *    below is redundant while that module is on. The server half is not: `Sprint` never sends
 *    anything, and the server is the side that was wrong.
 *
 * ── Timing rules this module is built on
 *
 * **Detection is per tick, not per edge.** On 1.8 the attack key is polled every tick, so holding
 * the button swings ~20 times a second and each swing clears the server's flag. An edge detector
 * (the previous implementation) fired once for a whole held combo and left every hit after the
 * first unrepaired.
 *
 * **The local hold spans a tick, not a frame.** `onRenderFrame` runs 60+ times a second against 20
 * ticks, so releasing on the next frame means releasing before any tick ever saw the claim — the
 * hold did nothing. It is held until two tick boundaries have passed instead, which also means a
 * module that claims sprint in the meantime cannot stomp it.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    /** Tick at which the current hold stops being applied; -1 when no hold is live. */
    @Volatile private var holdUntilTick: Int = -1

    /**
     * Wall-clock backstop for [holdUntilTick].
     *
     * A tick counter only moves when ticks move. If the client stops ticking (pause, world change)
     * the two-tick window would never end, and a sprint claim is applied straight to the player, not
     * through the keybind layer that a GUI suppresses — so it would sit there forcing sprint. Three
     * ticks of real time is longer than the window needs and short enough that a stall cannot hold
     * the flag.
     */
    @Volatile private var holdDeadlineNanos: Long = 0L
    private const val HOLD_MAX_NANOS = 150_000_000L

    /** Last tick an attack was handled on — one assertion per tick, not one per render frame. */
    @Volatile private var lastHandledTick: Int = -1

    fun reset() {
        lastHandledTick = -1
        releaseHold()
    }

    override fun onDisable() {
        reset()
    }

    /**
     * Give the sprint claim back.
     *
     * Releasing is not the same as switching sprint off: an unclaimed state means "no opinion", so
     * the flag we set survives and vanilla's own rules decide from the next tick on. This is what
     * keeps the hold from turning into a permanent forced sprint.
     */
    private fun releaseHold() {
        if (holdUntilTick < 0) return
        holdUntilTick = -1
        holdDeadlineNanos = 0L
        EventBridge.releaseSprint(name)
    }

    /**
     * Called from the platform render loop (MC main thread) every frame
     * (ForgeBootstrap: `KeepSprint.onRenderFrame(mc)`).
     *
     * The `mc` handle is unused: everything this module needs already lives on [EventBridge]. The
     * parameter stays because that is the shape the platform calls every other render hook with.
     */
    fun onRenderFrame(mc: Any) {
        try {
            if (!enabled) {
                reset()
                return
            }

            val tick = EventBridge.getCurrentTick()
            if (holdUntilTick >= 0 &&
                (tick > holdUntilTick || System.nanoTime() > holdDeadlineNanos)
            ) {
                releaseHold()
            }

            // "Attacking now" — physical left mouse OR an AutoClicker synthetic attack (the platform
            // samples the mouse at the end of the frame, so this can be up to one frame late).
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack
            if (!attacking) return
            if (tick == lastHandledTick) return

            // Only for a swing that landed. There is no hook into the attack method, so a live
            // entity under the crosshair is the proxy this family uses; a swing at air must not
            // assert anything.
            val target = EventBridge.crosshairTarget ?: return
            if (target.health <= 0f) return

            // Preserve a sprint, never create one. The sample is taken at the START of this tick
            // (EventBridge.onStartTick), i.e. the state the player attacked *from* — the live field
            // is unreliable here because vanilla clears and re-asserts it inside the same tick.
            // Without this gate a player standing still and clicking would be put into sprint,
            // complete with sprint particles and a flag the server did not expect.
            if (!EventBridge.localSprinting) return

            // A tap module currently owns W: it is resetting sprint on purpose, and the whole point
            // of a W-tap is the tick of walking knockback. Re-asserting here would undo the reset it
            // just paid for. Same principle as the key arbitration — a deliberate intent wins over
            // an automatic correction. (Ours is ADDITIVE, so this flag is never set by us.)
            if (EventBridge.syntheticForwardOverride) return

            lastHandledTick = tick

            // Local half: one full tick of "the flag stays on". ADDITIVE, so it never fights a
            // module that wants sprint off.
            EventBridge.claimSprint(name, true)
            holdUntilTick = tick + 1
            holdDeadlineNanos = System.nanoTime() + HOLD_MAX_NANOS

            // Server half, and the reason the module exists at all.
            //
            // Deliberately NOT gated on `serverSprintState`. That mirror tracks the LOCAL flag
            // (the platform's sprint setter reports into it), and in MC-69459 the local flag is
            // still true — the desync is precisely that the client thinks true while the server
            // holds false. Checking the mirror here would read our own optimism back and skip the
            // one packet that fixes it. The attack itself is the proof: the server ran the same
            // `setSprinting(false)`, so its copy is cleared whether or not we can see it.
            EventBridge.sendEntityAction("START_SPRINTING")

            markWorked() // HUD work flash — the sprint state survived the hit
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
