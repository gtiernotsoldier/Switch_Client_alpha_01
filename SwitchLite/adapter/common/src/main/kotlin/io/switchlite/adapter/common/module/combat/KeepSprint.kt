package io.switchlite.adapter.common.module.combat

import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*

/**
 * KeepSprint — no speed drop when attacking (Raven model).
 *
 * Vanilla MC reduces horizontal speed to ~60% when attacking. KeepSprint re-scales the player's
 * horizontal motion by the keep factor so the speed doesn't drop.
 *
 * Implementation: the actual motion scaling happens in the injected attack-method hook
 * (agent/KeepSprintBridge), which runs at the end of EntityPlayer.attackTargetEntityWithCurrentItem
 * on the MC main thread (Raven's model — apply right after the attack, perfect timing, no
 * cross-thread dance). This module only exposes the config (keep factor / Legit interpolation /
 * chance) that the bridge reads.
 *
 * Config:
 *   - HorizontalKeep (Normal mode) / Legit distance interpolation, Chance.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Mode ==========
    private val mode by choices("Mode", arrayOf("Normal", "Legit"))

    // ========== Speed ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Legit Mode: Distance-based interpolation ==========
    private val minReach by float("MinReach", 1.0f, 0f..1.5f, "blocks")
    private val maxReach by float("MaxReach", 3.0f, 2.5f..3.0f, "blocks")
    private val minKeep by float("MinKeep", 0.65f, 0.6f..0.7f)
    private val maxKeep by float("MaxKeep", 0.85f, 0.7f..0.95f)

    // ========== Probability ==========
    private val chance by probability("Chance", 100, 0..100)

    /** Current active keep factor (read by the injected attack-method bridge). */
    @Volatile
    var activeKeepFactor: Float = 1.0f
        private set

    /** Update the active keep factor from config (called by module infrastructure / bridge). */
    fun refreshKeepFactor() {
        activeKeepFactor = when (mode) {
            "Legit" -> minKeep // conservative default for the bridge (no distance context here)
            else -> horizontalKeep
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        refreshKeepFactor()
    }

    override fun onDisable() {
        activeKeepFactor = 1.0f
    }
}
