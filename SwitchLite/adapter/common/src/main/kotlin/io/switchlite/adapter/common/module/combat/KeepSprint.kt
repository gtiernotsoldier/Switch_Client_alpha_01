package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.strategy.keepsprint.KeepSprintConfig
import io.switchlite.core.strategy.keepsprint.KeepSprintStrategy
import io.switchlite.agent.MappingContext

/**
 * KeepSprint — keep sprinting through an attack (no speed drop).
 *
 * Essence: the vanilla attack drops speed for two reasons — MC internally switches sprinting→walking
 * (setSprinting(false)) AND multiplies horizontal motion by 0.6, both inside the attack method
 * (`func_71061_d_` / attackTargetEntityWithCurrentItem). This module counters that on the MC main
 * thread every render frame while attacking + moving:
 *   1. re-assert sprint (setSprinting(true)) to undo the state flip, and
 *   2. restore motion back up to the sprint cap (core restoreMotion) to undo the motion *= 0.6.
 *
 * Config semantics (Normal mode): `HorizontalKeep` is the fraction of the sprint speed to PRESERVE —
 * 1.0 = keep full sprint (no drop), 0.6 = vanilla. It is a THRESHOLD: at/above KEEP_THRESHOLD (0.9)
 * we keep sprint at all; below we leave vanilla alone.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  KNOWN LIMITATION — READ BEFORE toggling this module (verdict: DEPRECATED).
 *  This per-frame "re-assert after the fact" approach is NOT stable. Vanilla cancels
 *  sprint + cuts motion EVERY swing inside the attack method, i.e. at click-CPS rate.
 *  A render-frame reassert (≈60Hz) races the tick/CPS-level destruction (10–20Hz), so
 *  the outcome depends entirely on CPS and frame timing — at high CPS the cancellation
 *  outruns the recovery and KeepSprint visibly fails / is hit-or-miss. Cannot be fixed
 *  from this layer without ALSO making it detectable on servers.
 *
 *  The ONLY CPS-independent, stable solution is to intercept the attack method AT THE
 *  SOURCE — replace the motion*=0.6 constant and swallow setSprinting(false) inside
 *  func_71061_d_ (LiquidBounce's `@ModifyConstant(0.6)` + `@Redirect(setSprinting)`).
 *  That requires bytecode injection (agent), which the team deliberately avoids here
 *  because this behavior is easy for anticheats to flag.
 *
 *  Recommended disposition: leave the per-frame reassert OFF by default; if this module
 *  is ever needed for real, implement the injection-based source interception in
 *  agent/Transformer + a KeepSprintBridge (see git history for the earlier approach).
 *  The pure-reflection restore (motion + jitter) is retained below mainly as a fallback
 *  for low-CPS casual play and as a reference implementation.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    /** Keep fraction at/above which we keep sprint at all (below = leave vanilla alone). */
    private const val KEEP_THRESHOLD = 0.9f

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ========== Normal: fixed speed keep ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Legit: distance-simulated keep (no target needed) ==========
    private val minReach by float("MinReach", 1.0f, 0f..1.5f, "blocks")
    private val maxReach by float("MaxReach", 3.0f, 2.5f..3.0f, "blocks")
    private val minKeep by float("MinKeep", 0.65f, 0.6f..0.7f)
    private val maxKeep by float("MaxKeep", 0.85f, 0.7f..0.95f)

    // ========== Probability (per attack action) ==========
    private val chance by probability("Chance", 100, 0..100)

    // ========== Per-swing / runtime state ==========
    /** Previous frame's attacking state — to detect the attack rising edge. */
    @Volatile private var prevAttacking: Boolean = false

    /** Whether the current attack action passed the chance roll (i.e. we keep this swing). */
    @Volatile private var keepThisSwing: Boolean = false

    /** Current keep fraction for this swing. */
    @Volatile private var keepFraction: Float = 1.0f

    /** Natural sprint speed cap (m/tick), tracked as a running max while sprinting + moving. */
    @Volatile private var sprintCap: Double = 0.28

    /** Current keep factor, exposed for diagnostics. */
    @Volatile
    var activeKeepFactor: Float = 1.0f
        private set

    // ========== Lifecycle ==========
    override fun onDisable() {
        reset()
    }

    private fun reset() {
        activeKeepFactor = 1.0f
        keepThisSwing = false
        prevAttacking = false
        keepFraction = 1.0f
        sprintCap = 0.28
    }

    private fun buildConfig(): KeepSprintConfig {
        return KeepSprintConfig(
            mode = mode,
            horizontalKeep = horizontalKeep,
            minReach = minReach, maxReach = maxReach,
            minKeep = minKeep, maxKeep = maxKeep,
            chance = chance.current, hurtTimeMax = 10, delayTicks = 0, cooldownTicks = 0
        )
    }

    /**
     * Called from the platform render loop (MC main thread) every frame.
     */
    fun onRenderFrame(mc: Any) {
        try {
            if (!enabled) { reset(); return }

            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return

            val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
            val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0
            val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as? Double ?: 0.0
            val currentSpeed = kotlin.math.sqrt(motionX * motionX + motionZ * motionZ)
            val moving = currentSpeed > 0.001

            // "Attacking now" — physical left mouse OR AutoClicker synthetic attack.
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack

            // Track the natural sprint cap as a running max while sprinting + moving (also during an
            // attack — the max absorbs the pre-slowdown speed). Stable, never a stale 0.28-only value.
            val sprinting = MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false
            if (sprinting && moving && currentSpeed > sprintCap) {
                sprintCap = currentSpeed
            }

            // Rising edge of an attack action → roll chance and decide this swing's keep fraction.
            val attackEdge = attacking && !prevAttacking
            prevAttacking = attacking
            if (attackEdge) {
                val config = buildConfig()
                keepThisSwing = KeepSprintStrategy.shouldActivate(config)
                if (keepThisSwing) {
                    val simDist = if (config.maxReach > config.minReach) {
                        minReach + kotlin.random.Random.nextFloat() * (maxReach - minReach)
                    } else maxReach
                    keepFraction = KeepSprintStrategy.keepPercentage(config, mode, simDist)
                }
            }

            if (!attacking || !moving) return
            if (!keepThisSwing) return
            if (keepFraction < KEEP_THRESHOLD) return // below threshold → leave vanilla alone

            // 1) Undo the sprinting→walking flip: re-assert sprint on the main thread.
            EventBridge.setSprinting(true)

            // 2) Undo the motion *= 0.6: restore horizontal speed toward the cap, with a small random
            //    jitter (0.97..1.0) so kept speed isn't a frozen constant. Clamped — never overshoots.
            val jitter = 0.97 + kotlin.random.Random.nextFloat() * 0.03
            val restored = KeepSprintStrategy.restoreMotion(
                motionX, motionY, motionZ, sprintCap * keepFraction, jitter
            ) ?: return
            MappingContext.getField("forge:entity_motionX")?.setDouble(player, restored.x)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, restored.z)
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
