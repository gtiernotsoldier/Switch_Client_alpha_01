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
 * Essence (as the user clarified): the vanilla attack drops speed because MC internally switches
 * the sprinting state away (sprinting → walking) on attack. KeepSprint is literally about KEEPING
 * that sprinting state. So we don't hand-compute motion — we simply re-assert sprint on the MC
 * main thread every frame while attacking + moving. Holding sprint true means the vanilla switch
 * away is undone, so speed naturally stays at sprint (no 60% drop), and since it's only re-asserting
 * an already-desired state it never accelerates.
 *
 * Per-swing model:
 *   - An "attack action" = physical left mouse press, or AutoClicker's synthetic attack pulse
 *     (rising edge of EventBridge.syntheticAttack). Each swing rolls [Chance] once.
 *   - If the roll passes, this swing's keep fraction is decided:
 *       * Normal → fixed [HorizontalKeep].
 *       * Legit → a *simulated* random distance in [MinReach, MaxReach] interpolated to a keep
 *         fraction in [MinKeep, MaxKeep]. No target/entity required.
 *   - HorizontalKeep acts as a THRESHOLD on that fraction: at/above KEEP_THRESHOLD (0.9) we fully
 *     keep sprint (never switch away); below, we leave vanilla alone (allows the 60% drop). This
 *     matches the user's "100% = don't switch, 60% = vanilla" semantics.
 *
 * Algorithm (keep fraction, chance) lives in core (KeepSprintStrategy); this module is orchestration
 * + platform landing. Everything runs on the MC main thread render loop (no packets needed).
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    /** Keep fraction at/above which we re-assert sprint every frame (below = vanilla). */
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

    /** Current keep fraction for this swing, exposed for diagnostics. */
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
            val currentSpeed = kotlin.math.sqrt(motionX * motionX + motionZ * motionZ)
            val moving = currentSpeed > 0.001

            // "Attacking now" — physical left mouse OR AutoClicker synthetic attack.
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack

            // Rising edge of an attack action → roll chance and decide this swing's keep fraction.
            val attackEdge = attacking && !prevAttacking
            prevAttacking = attacking
            if (attackEdge) {
                val config = buildConfig()
                keepThisSwing = KeepSprintStrategy.shouldActivate(config)
                if (keepThisSwing) {
                    // Simulated distance (no target needed) → keep fraction.
                    val simDist = if (config.maxReach > config.minReach) {
                        minReach + kotlin.random.Random.nextFloat() * (maxReach - minReach)
                    } else maxReach
                    activeKeepFactor = KeepSprintStrategy.keepPercentage(config, mode, simDist)
                }
            }

            if (!attacking || !moving || !keepThisSwing) return

            // The essence: keep the SPRINTING state from being switched away on attack. MC flips
            // sprinting→walking when you attack; we re-assert sprint on the main thread every frame.
            // HorizontalKeep is a threshold: at/above KEEP_THRESHOLD we fully keep sprint; below we
            // leave vanilla alone. Re-asserting an already-desired sprint never accelerates.
            if (activeKeepFactor >= KEEP_THRESHOLD) {
                EventBridge.setSprinting(true)
            }
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
