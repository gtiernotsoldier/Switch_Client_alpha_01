package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.*
import io.doppel.adapter.common.api.EventBridge
import io.doppel.agent.MappingContext
import kotlin.math.sqrt

/**
 * KeepSprint — port of Raven's `module/impl/movement/KeepSprint.java`.
 *
 * Vanilla 1.8.9 ends a successful attack with (EntityPlayer.attackTargetEntityWithCurrentItem):
 *
 *     this.motionX *= 0.6D;
 *     this.motionZ *= 0.6D;
 *     this.setSprinting(false);
 *
 * Raven's mixin (MixinEntityPlayer) replaces that block when KeepSprint is enabled with
 * `KeepSprint.keepSprint(target)`:
 *
 *     mult = (100 - slow) / 100;              // slow = "Slow %", 0..40, default 40 → 0.6
 *     motionX *= mult; motionZ *= mult;       // the damp the user still wants (40 == vanilla)
 *     if (stopSprint) motionX *= 0.5;         // Raven's extra "stop sprint" damp, X only
 *     // setSprinting(false) is NOT called: the sprint state survives the hit.
 *
 * The point of the module is that last line: the sprint FLAG survives the reset, so the next hit is
 * still a sprint hit (extra knockback) and the client does not fall back to walking, while the
 * motion damp the player configures (default: exactly vanilla's 0.6) keeps it looking normal — that
 * is the "不损失速度 while staying legit" balance: `Slow % = 0` keeps the speed, `Slow % = 40`
 * (default) keeps only the sprint state and looks exactly like vanilla.
 *
 * This module layer cannot intercept the attack method (the platform deliberately has no injection
 * for it), so it runs on the render frame that follows the tick which processed the click — i.e.
 * after vanilla has already applied `motion *= 0.6` and `setSprinting(false)`. It therefore undoes
 * the vanilla damp and applies Raven's factor (net scale = mult / 0.6) and re-asserts the sprint
 * flag vanilla cleared. The correction is idempotent-safe: the result is clamped so it can never
 * exceed the fastest speed the player was observed sprinting at, so a stray frame can never turn
 * this into a speed boost.
 *
 * ⚠ Interactions with the other sprint-state modules (same package):
 *  - `WTap` / `STap` release W / press S to reset the sprint by *input*, and `SprintReset` sends the
 *    C0B stop/start pair so the SERVER sees a sprint reset. KeepSprint deliberately re-asserts
 *    sprinting on every swing it preserves, which is the opposite intent: running them together
 *    means both write the same game state in the same tick and the winner depends on tick order.
 *    Pick one side of the reset (Raven has the same conflict — its `Reduce` module is documented as
 *    "Overrides KeepSprint", and its mixin calls only one of the two).
 *  - `Sprint` (movement) already re-asserts sprint every tick while moving forward, which makes
 *    KeepSprint's sprint re-assert redundant (the motion correction is unaffected).
 *  - `ADTap` only touches A/D; no interaction.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Config (Raven's option set) ==========
    /**
     * Raven: `SliderSetting("Slow %", 40.0D, 0.0D, 40.0D, 1.0D)`.
     * Percentage of horizontal motion removed after a hit; 40 == vanilla's 0.6, 0 == keep it all.
     */
    private val slow by float("Slow", 40f, 0f..40f, "%", step = 1.0)

    /** Raven: `ButtonSetting("Stop Sprint", true)` — extra `motionX *= 0.5`. */
    private val stopSprint by boolean("StopSprint", true)

    /** Raven: `ButtonSetting("Disable while jumping", false)` — vanilla behaviour in the air. */
    private val disableWhileJump by boolean("DisableWhileJump", false)

    /** Raven: `ButtonSetting("Only reduce reach hits", false)` — close hits stay vanilla. */
    private val onlyReduceReachHits by boolean("OnlyReduceReachHits", false)

    // ========== Runtime state ==========
    /** Previous frame's attack input state — to detect the rising edge of an attack. */
    @Volatile private var prevAttacking: Boolean = false

    /**
     * Fastest horizontal speed seen while sprinting and moving (blocks/tick). Used only as a ceiling:
     * the correction may restore speed up to the speed the player has already reached themselves,
     * never beyond. 0.0 = nothing observed yet, which disables the ceiling rather than guessing.
     */
    @Volatile private var sprintCap: Double = 0.0

    fun reset() {
        prevAttacking = false
        sprintCap = 0.0
    }

    /**
     * The resolved option values (Raven's exact option set), for tests of the option wiring —
     * reading them also forces the delegates to register, so their metadata can be asserted too.
     */
    internal fun tunables(): KeepSprintTunables =
        KeepSprintTunables(slow, stopSprint, disableWhileJump, onlyReduceReachHits)

    override fun onDisable() {
        reset()
    }

    /**
     * Called from the platform render loop (MC main thread) every frame
     * (ForgeBootstrap: `KeepSprint.onRenderFrame(mc)`).
     */
    fun onRenderFrame(mc: Any) {
        try {
            if (!enabled) {
                reset()
                return
            }

            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return

            val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
            val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0
            val speed = sqrt(motionX * motionX + motionZ * motionZ)

            // Track the natural sprint speed while the player is actually sprinting (the vanilla
            // sprint cap), before the hit clears the flag. Running max only — a slowdown never lowers
            // the ceiling.
            val sprinting = MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false
            if (sprinting && speed > sprintCap) sprintCap = speed

            // "Attacking now" — physical left mouse OR an AutoClicker synthetic attack (the platform
            // samples the mouse at the end of the frame, so this is up to one frame behind the click).
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack
            val attackEdge = attacking && !prevAttacking
            prevAttacking = attacking
            if (!attackEdge) return

            // Raven's branch runs only for a hit that actually landed. Best available proxy here: a
            // live entity under the crosshair at the moment of the swing. Without it a swing at air
            // would damp motion although vanilla never did (and, with the ceiling unset, could push
            // the player faster than vanilla would).
            val target = EventBridge.crosshairTarget ?: return
            if (target.health <= 0f) return

            val onGround = MappingContext.getFieldValue(player, "forge:entity_onGround") as? Boolean ?: false
            // Raven's `vanilla` branch: leave vanilla's damp + sprint stop completely alone.
            // (Raven also requires `!capabilities.isCreativeMode` here — no hook for that yet.)
            if (KeepSprintLogic.useVanilla(disableWhileJump, onGround, onlyReduceReachHits, target.distance)) return

            val corrected = KeepSprintLogic.correct(motionX, motionZ, slow, stopSprint, sprintCap) ?: return
            MappingContext.getField("forge:entity_motionX")?.setDouble(player, corrected.motionX)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, corrected.motionZ)

            // Raven never calls setSprinting(false) on this path; vanilla already did (that is the
            // "sprint reset" the module exists to survive), so re-assert the flag here.
            EventBridge.setSprinting(true)
            markWorked() // HUD work flash — this swing's speed/sprint was preserved
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}

/**
 * KeepSprint's resolved option values — Raven's option set, nothing else.
 */
internal data class KeepSprintTunables(
    val slow: Float,
    val stopSprint: Boolean,
    val disableWhileJump: Boolean,
    val onlyReduceReachHits: Boolean
)

/**
 * KeepSprint's decision math, split out of the module so it is pure and unit-testable.
 */
object KeepSprintLogic {

    /** Vanilla 1.8.9 attack damp: `motionX *= 0.6D; motionZ *= 0.6D;`. */
    const val VANILLA_DAMP: Double = 0.6

    /** Raven's `Slow %` slider bounds. */
    const val SLOW_MIN: Float = 0f
    const val SLOW_MAX: Float = 40f

    /** Raven: `if (distance != -1.0 && distance <= 3.0) vanilla = true;`. */
    const val REACH_HIT_DISTANCE: Float = 3.0f

    /**
     * The factor Raven applies *instead of* vanilla's 0.6: `(100 - slow) / 100`.
     * 40 % → 0.6 (identical to vanilla), 0 % → 1.0 (no damp at all).
     */
    fun keepFactor(slowPercent: Float): Double =
        (100.0 - slowPercent.coerceIn(SLOW_MIN, SLOW_MAX).toDouble()) / 100.0

    /**
     * Raven's `vanilla` short-circuit: when true the module must not touch motion or sprint.
     *
     * @param hitDistance distance to the entity that was hit, in blocks (the crosshair target).
     */
    fun useVanilla(
        disableWhileJump: Boolean,
        onGround: Boolean,
        onlyReduceReachHits: Boolean,
        hitDistance: Float
    ): Boolean {
        if (disableWhileJump && !onGround) return true
        if (onlyReduceReachHits && hitDistance <= REACH_HIT_DISTANCE) return true
        return false
    }

    data class Correction(val motionX: Double, val motionZ: Double)

    /**
     * Undo vanilla's damp and apply Raven's factor to the current horizontal motion.
     *
     * @param sprintCap ceiling for the result (0.0 = no ceiling observed yet).
     * @return the motion to write, or null when there is nothing meaningful to do.
     */
    fun correct(
        motionX: Double,
        motionZ: Double,
        slowPercent: Float,
        stopSprint: Boolean,
        sprintCap: Double
    ): Correction? {
        val speed = sqrt(motionX * motionX + motionZ * motionZ)
        if (speed < 1e-4) return null // standing still — nothing to preserve, and 0 would divide

        // Vanilla already multiplied by 0.6, so divide it back out and apply Raven's factor:
        // post-hit speed becomes pre-hit speed * keepFactor.
        val scale = keepFactor(slowPercent) / VANILLA_DAMP
        var x = motionX * scale
        var z = motionZ * scale

        // Raven: `if (stopSprint.isToggled()) mc.thePlayer.motionX *= 0.5;` — X only, exactly as the
        // reference does it (it is the "the player stopped sprinting" damp the server expects to see).
        if (stopSprint) x *= 0.5

        // Never accelerate past a speed the player already reached while sprinting: the divide above
        // assumes vanilla's damp ran, and on the frames where it did not (a hit that vanilla skipped,
        // a click the tick has not processed yet) it would otherwise be a speed boost.
        if (sprintCap > 0.0) {
            val resultSpeed = sqrt(x * x + z * z)
            if (resultSpeed > sprintCap) {
                val clamp = sprintCap / resultSpeed
                x *= clamp
                z *= clamp
            }
        }
        return Correction(x, z)
    }
}
