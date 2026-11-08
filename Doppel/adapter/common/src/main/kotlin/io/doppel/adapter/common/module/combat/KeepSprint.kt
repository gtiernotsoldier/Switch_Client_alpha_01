package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.api.EventBridge
import io.doppel.contract.model.PlayerState
import io.doppel.ipc.KeepSprintAssert
import io.doppel.ipc.KeepSprintHold
import io.doppel.ipc.Strategies

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
 *    module stands down while a tap owns the W key (see the marshalled `forwardOverride`) — the tap is
 *    deliberate, the preserve is automatic. `STap` sets sprint off by pressing S instead and owns a
 *    different key, so the two still disagree on the sprint state: pick one.
 *  - `Sprint` (movement) already re-asserts sprint every tick you hold forward, so the local half
 *    below is redundant while that module is on. The server half is not: `Sprint` never sends
 *    anything, and the server is the side that was wrong.
 *
 * ── Where the state lives now
 *
 * This side holds only **judgements + marshalling + platform writes**: the attack composite
 * (physical left mouse OR an AutoClicker synthetic attack — both refreshed per frame), the
 * crosshair target being alive, the forward-key arbitration and the tap-owns-W override.
 * Everything stateful lives in the algorithm process (`strategy::keep_sprint`): the sprint-sample
 * latch, the two-dispatch hold window, the wall-clock backstop and the per-tick assert dedup.
 * There is no `kotlin.random` and no local time window — `System.nanoTime()` is an input we
 * marshal. The core answers with a hold *status* plus an assert step, and DEAD/`null` both mean
 * "give any claim you still hold back" — an unclaimed state means "no opinion", which is also the
 * self-heal for a hold dropped behind our back by another module's Reset or a core restart.
 *
 * ── Timing rules this module is built on (now core-side, enforced by the parity fixture)
 *
 * **Detection is per tick, not per edge.** On 1.8 the attack key is polled every tick, so holding
 * the button swings ~20 times a second and each swing clears the server's flag. An edge detector
 * (an earlier implementation) fired once for a whole held combo and left every hit after the
 * first unrepaired.
 *
 * **The local hold spans a tick, not a frame.** `onRenderFrame` runs 60+ times a second against 20
 * ticks, so releasing on the next frame means releasing before any tick ever saw the claim — the
 * hold did nothing. It is held until two tick boundaries have passed instead (or until the player
 * lets go of forward, or a wall-clock backstop expires, whichever comes first), which also means a
 * module that claims sprint in the meantime cannot stomp it.
 *
 * **The ask is frame-driven, and that is deliberate.** This is the first frame-rate op: the attack
 * flag is refreshed once per render frame, and the wall-clock backstop must be checked on a thread
 * that survives a stalled tick dispatcher (render keeps running while the 20 Hz dispatch stops).
 * Idle frames cost nothing — the short-circuit below is exactly the original's early returns
 * re-ordered, so a frame with no attack, no sprinting sample and no live claim never crosses the
 * wire — and there is no random stream for the frame rate to drive, which was the reason HitSelect
 * refuses to ask per frame.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    /**
     * Called from the platform render loop (MC main thread) every frame
     * (ForgeBootstrap: `KeepSprint.onRenderFrame(mc)`).
     *
     * The `mc` handle is unused: everything this module needs already lives on [EventBridge]. The
     * parameter stays because that is the shape the platform calls every other render hook with.
     */
    fun onRenderFrame(mc: Any) {
        try {
            if (!enabled) return

            // "Attacking now" — physical left mouse OR an AutoClicker synthetic attack (the platform
            // samples the mouse at the end of the frame, so this can be up to one frame late).
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack

            // The original's early returns, re-ordered into one guard: a frame with no attack, no
            // sprinting sample and no live claim can change nothing, so it never crosses the wire.
            // The latch only refreshes on a *sprinting* sample (the core stamps the same way), a
            // release needs a live hold (the claim is its platform shadow), and an assert needs an
            // attack — each predicate is the original's own, no new judgement.
            if (!attacking && !EventBridge.localSprinting && !EventBridge.hasSprintClaim(name)) return

            // Only for a swing that landed. There is no hook into the attack method, so a live
            // entity under the crosshair is the proxy this family uses; a swing at air must not
            // assert anything.
            val target = EventBridge.crosshairTarget
            val targetAlive = target != null && target.health > 0f

            val step = Strategies.keepSprint(
                PlayerState.EMPTY, target,
                System.nanoTime(),
                EventBridge.getCurrentTick(),
                EventBridge.localSprinting,
                attacking,
                targetAlive,
                EventBridge.isKeyForwardActive,
                EventBridge.syntheticForwardOverride
            )

            // No answer: give any claim we still hold back. An unclaimed state means "no opinion",
            // so the flag we set survives and vanilla's own rules decide from the next tick on —
            // the same direction as the release, and it cannot leave a forced sprint behind.
            if (step == null) {
                if (EventBridge.hasSprintClaim(name)) EventBridge.releaseSprint(name)
                return
            }

            // Hold upkeep: RELEASE ended the hold this frame; DEAD means the core holds nothing
            // (a Reset or a restart dropped it) — both give the claim back. Only LIVE keeps it.
            if (step.hold != KeepSprintHold.LIVE && EventBridge.hasSprintClaim(name)) {
                EventBridge.releaseSprint(name)
            }

            if (step.assert == KeepSprintAssert.ASSERT) {
                // Local half: one full tick of "the flag stays on". ADDITIVE, so it never fights a
                // module that wants sprint off.
                EventBridge.claimSprint(name, true)

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
            }
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }

    override fun onEnable() {
        // A fresh enable must not inherit a latch, a hold window or an
        // assert dedup the core still believes it owns.
        Strategies.resetPlayer()
    }

    override fun onDisable() {
        // The claim itself is released by Module.disable() -> releaseAllClaims,
        // which hands the sprint decision back to whatever else wants it.
        Strategies.resetPlayer()
    }
}
