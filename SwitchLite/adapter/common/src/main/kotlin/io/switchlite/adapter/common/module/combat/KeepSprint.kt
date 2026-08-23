package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.strategy.keepsprint.KeepSprintConfig
import io.switchlite.core.strategy.keepsprint.KeepSprintStrategy
import io.switchlite.agent.MappingContext
import io.switchlite.core.logging.CoreLogger

/**
 * KeepSprint — keep sprinting through an attack (no speed drop).
 *
 * Essence: the vanilla attack drops speed for two reasons — MC internally switches sprinting→walking
 * (setSprinting(false)) AND multiplies horizontal motion by 0.6, both inside the attack method. The
 * only reliable way to counter this without bytecode injection is, on the MC main thread every render
 * frame while attacking + moving:
 *   1. re-assert sprint (setSprinting(true)) to undo the state flip, and
 *   2. restore motion back up to the sprint cap (restoreMotion) to undo the motion *= 0.6.
 *
 * Anti-detection: we never inject and we add natural jitter — the restore target is the sprint cap
 * times a small random factor near 1.0, so kept speed isn't a frozen constant (a perfectly flat speed
 * every swing is what a server heuristic would flag). Each swing also rolls [Chance], and Legit mode
 * randomizes the keep fraction via a simulated distance (no target needed).
 *
 * Algorithm (chance, keep fraction, jittered restore) lives in core (KeepSprintStrategy); this module
 * is orchestration + platform landing, all on the MC main thread render loop (no packets).
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

    // ========== Diagnostic (interval measurement) ==========
    /** Whether the one-shot probe has fired (confirms onRenderFrame is reached). */
    @Volatile private var probeLogged = false
    /** Frame counter for the diagnostic. */
    @Volatile private var diagFrame = 0
    /** Frames since the last attack rising edge, measured while attacking. */
    @Volatile private var framesSinceAttackEdge = 0
    /** When sprint was observed switched OFF despite us wanting keep. */
    @Volatile private var sprintSwitchOffObserved = false

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
        probeLogged = false
        diagFrame = 0
        framesSinceAttackEdge = 0
        sprintSwitchOffObserved = false
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

            // PROBE: fire once on first enable to confirm this function is even called and what the
            // key signals read. This is a one-shot diagnostic — it does NOT depend on the entry
            // conditions below, so if it prints we know the hook + module are alive.
            if (!probeLogged) {
                probeLogged = true
                CoreLogger.info(
                    "[KeepSprint.PROBE] onRenderFrame reached! playerNull=false " +
                    "enabled=$enabled attacking=$attacking (phys=${EventBridge.isLeftMousePhysicallyDown} " +
                    "synth=${EventBridge.syntheticAttack}) moving=$moving speed=$currentSpeed"
                )
            }

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
                framesSinceAttackEdge = 0
                sprintSwitchOffObserved = false
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

            framesSinceAttackEdge++

            // ── Diagnostic: quantify the interval (the gap between "sprint switched away" and our
            //    restore). We log only while a swing is being kept, throttled, to a limited burst.
            if (keepThisSwing && keepFraction >= KEEP_THRESHOLD && (++diagFrame % 5 == 0)) {
                val speedDeficit = ((sprintCap * keepFraction - currentSpeed) / (sprintCap * keepFraction) * 100).toInt()
                // Detect whether MC flipped sprint off this frame (the "interval" we care about).
                if (!sprinting) sprintSwitchOffObserved = true
                CoreLogger.info(
                    "[KeepSprint] frame=$framesSinceAttackEdge sprintNow=$sprinting " +
                    "speed=$currentSpeed cap=${"%.3f".format(sprintCap)} keep=${"%.2f".format(keepFraction)} " +
                    "deficit%=$speedDeficit switchedOff=$sprintSwitchOffObserved"
                )
            }

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
