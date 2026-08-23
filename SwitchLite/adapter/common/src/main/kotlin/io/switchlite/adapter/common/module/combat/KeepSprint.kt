package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.strategy.keepsprint.KeepSprintConfig
import io.switchlite.core.strategy.keepsprint.KeepSprintStrategy
import io.switchlite.agent.MappingContext

/**
 * KeepSprint — no speed drop when attacking.
 *
 * Essence: when the player is sprinting + attacking + moving, vanilla MC multiplies horizontal
 * motion (motionX/Z) by ~0.6 on a swing. This module restores the lost speed back toward the
 * player's natural sprint speed so the attack doesn't slow him down. It only ever restores up to
 * the natural sprint baseline (capped) — it never accelerates past it.
 *
 * Per-swing model:
 *   - An "attack action" = physical left mouse press, or AutoClicker's synthetic attack pulse
 *     (rising edge of EventBridge.syntheticAttack). Each swing rolls [Chance] once.
 *   - If the roll passes, this swing's keep percentage is decided:
 *       * Normal → fixed [HorizontalKeep] (1.0 = keep full sprint speed).
 *       * Legit → a *simulated* random distance in [MinReach, MaxReach] is interpolated to a keep
 *         percentage in [MinKeep, MaxKeep]. No target/entity is required.
 *   - While the attack is held + moving, motion is restored toward (natural sprint baseline × keep%).
 *
 * Algorithm (keep percentage, chance, compounding-proof clamp) lives in core
 * (KeepSprintStrategy). This module is orchestration + platform landing, and it measures the
 * natural sprint baseline so the restore ceiling is the player's real sprint speed, not a constant.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

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
    /** Natural sprint speed (m/tick), measured while sprinting & moving & not attacking. */
    @Volatile private var sprintBaseline: Double = 0.0

    /** Previous frame's attacking state — to detect the attack rising edge. */
    @Volatile private var prevAttacking: Boolean = false

    /** Whether the current attack action passed the chance roll (i.e. we keep this swing). */
    @Volatile private var keepThisSwing: Boolean = false

    /** Target horizontal speed (m/tick) for the current swing = sprintBaseline × keep%. */
    @Volatile private var swingTargetSpeed: Double = 0.0

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
        sprintBaseline = 0.0
        keepThisSwing = false
        prevAttacking = false
        swingTargetSpeed = 0.0
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
            val sprinting = MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false

            val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
            val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0
            val currentSpeed = kotlin.math.sqrt(motionX * motionX + motionZ * motionZ)
            val moving = currentSpeed > 0.001

            // "Attacking now" — physical left mouse OR AutoClicker synthetic attack.
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack

            // Track the natural sprint ceiling as a running max while sprinting + moving. This runs
            // even during an attack (the max absorbs the pre-slowdown speed), so the restore target
            // is a stable, real value — it never resets to 0 the instant you start attacking and
            // never depends on a stale pre-attack sample. This is what kills the "hit-or-miss"
            // speed: every attack restores toward the same sprint cap.
            if (sprinting && moving) {
                if (currentSpeed > sprintBaseline) sprintBaseline = currentSpeed
            }

            // Rising edge of an attack action → roll chance and decide this swing's keep target.
            val attackEdge = attacking && !prevAttacking
            prevAttacking = attacking
            if (attackEdge) {
                val config = buildConfig()
                keepThisSwing = KeepSprintStrategy.shouldActivate(config)
                if (keepThisSwing) {
                    // Simulated distance (no target needed) → keep % → target speed.
                    val simDist = if (config.maxReach > config.minReach) {
                        minReach + kotlin.random.Random.nextFloat() * (maxReach - minReach)
                    } else maxReach
                    val keepPct = KeepSprintStrategy.keepPercentage(config, mode, simDist)
                    // Cap = max(vanilla sprint floor, running-max baseline). The floor (0.28) makes
                    // the FIRST attack work immediately; the running max absorbs speed boosts so the
                    // ceiling raises dynamically. Never falls back to a stale hardcode.
                    val cap = KeepSprintStrategy.effectiveCap(config.sprintBaseSpeed, sprintBaseline)
                    swingTargetSpeed = cap * keepPct
                    activeKeepFactor = keepPct / KeepSprintStrategy.VANILLA_ATTACK_SLOWDOWN
                }
            }

            if (!attacking || !moving || !keepThisSwing) return

            // Restore toward the swing target (clamped, never overshoots the baseline).
            val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as? Double ?: 0.0
            val restored = KeepSprintStrategy.restoreToTargetSpeed(
                motionX, motionY, motionZ, swingTargetSpeed
            ) ?: return

            MappingContext.getField("forge:entity_motionX")?.setDouble(player, restored.x)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, restored.z)
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
