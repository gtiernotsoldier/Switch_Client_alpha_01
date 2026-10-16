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
 *  - `WTap` / `STap` reset the sprint *on purpose* by releasing W / pressing S. That is the opposite
 *    intent; the key arbiter stops them fighting over the keys, but the two still want different
 *    sprint states — pick one.
 *  - `Sprint` (movement) already re-asserts sprint every tick you hold forward, so the local half
 *    below is redundant while that module is on. The server half is not: `Sprint` never sends
 *    anything, and the server is the side that was wrong.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    /** Previous render frame's attack input state — to detect the rising edge of a swing. */
    @Volatile private var prevAttacking: Boolean = false

    /** True while the one-frame local hold from the last swing is still in place. */
    @Volatile private var holdActive: Boolean = false

    fun reset() {
        prevAttacking = false
    }

    override fun onDisable() {
        reset()
        if (holdActive) {
            EventBridge.releaseSprint(name)
            holdActive = false
        }
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

            // Drop last frame's hold. Releasing is not the same as switching sprint off — an
            // unclaimed state means "no opinion", so the flag we set last frame survives and
            // vanilla's own rules get to decide from the next tick on.
            if (holdActive) {
                EventBridge.releaseSprint(name)
                holdActive = false
            }

            // "Attacking now" — physical left mouse OR an AutoClicker synthetic attack (the platform
            // samples the mouse at the end of the frame, so this can be up to one frame late).
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack
            val attackEdge = attacking && !prevAttacking
            prevAttacking = attacking
            if (!attackEdge) return

            // Only for a swing that landed. There is no hook into the attack method, so a live
            // entity under the crosshair is the proxy this family uses; a swing at air must not
            // assert anything.
            val target = EventBridge.crosshairTarget ?: return
            if (target.health <= 0f) return

            // Local half: hold the flag across the tick vanilla clears it. One frame is enough —
            // vanilla's own start-sprinting rule re-asserts it from the held key on the next tick
            // if the player is still moving forward, and its own rules still get to switch it off if
            // they are not. ADDITIVE, so it never fights a module that wants sprint off.
            EventBridge.claimSprint(name, true)
            holdActive = true

            // Server half, and the reason the module exists at all. Both sides clear the flag on
            // every landed attack, but the client never *appears* to change, so it never sends the
            // C0B and the server keeps believing the player is walking (MC-69459). It is the
            // server's copy that decides whether the next hit is a sprint hit.
            if (!EventBridge.serverSprintState) {
                EventBridge.sendEntityAction("START_SPRINTING")
            }

            markWorked() // HUD work flash — the sprint state survived the hit
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
