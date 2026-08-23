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
 * Essence (the simplest, faithful model):
 *   When the player is sprinting and attacks, vanilla MC multiplies horizontal motion (motionX/Z)
 *   by ~0.6 on the swing. This module, while the player is sprinting + attacking + moving, restores
 *   the horizontal speed back up to the sprint baseline so the attack doesn't slow him down.
 *   No weapon check, no target check.
 *
 * Implementation:
 *   Runs on the MC main thread every render frame (called from ForgeBootstrap.render).
 *   - "sprinting" = player_isSprinting (there's a sprint to keep).
 *   - "attacking" = physical left mouse down OR AutoClicker's synthetic attack (EventBridge).
 *   - "moving" = horizontal motion magnitude non-negligible.
 *   When all hold, compute the compounding-proof restore via core and write motionX/Z.
 *
 *   Restore is a clamp to an absolute target (sprintBaseSpeed * horizontalKeep), NOT a per-frame
 *   multiply — so frames where no swing happened can never over-boost. Algorithm lives in core
 *   (KeepSprintStrategy.restoreToTargetSpeed); this module is orchestration + platform landing.
 */
object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    // ========== Speed ==========
    private val horizontalKeep by float("HorizontalKeep", 1.0f, 0.6f..1.0f)

    // ========== Lifecycle ==========
    override fun onDisable() {
        activeKeepFactor = 1.0f
    }

    /** Current active keep factor, exposed for diagnostics. */
    @Volatile
    var activeKeepFactor: Float = 1.0f
        private set

    private fun buildConfig(): KeepSprintConfig {
        return KeepSprintConfig(
            mode = "Normal",
            horizontalKeep = horizontalKeep,
            minReach = 1.0f, maxReach = 3.0f,
            minKeep = 0.65f, maxKeep = 0.85f,
            chance = 100, hurtTimeMax = 10, delayTicks = 0, cooldownTicks = 0
        )
    }

    /**
     * Called from the platform render loop (MC main thread) every frame.
     * Restores sprint speed while sprinting + attacking + moving.
     */
    fun onRenderFrame(mc: Any) {
        try {
            if (!enabled) { activeKeepFactor = 1.0f; return }

            val player = MappingContext.getFieldValue(mc, "forge:mc_thePlayer") ?: return
            val sprinting = MappingContext.invokeMethod(player, "forge:player_isSprinting") as? Boolean ?: false
            val attacking = EventBridge.isLeftMousePhysicallyDown || EventBridge.syntheticAttack
            if (!sprinting || !attacking) { activeKeepFactor = 1.0f; return }

            val motionX = MappingContext.getFieldValue(player, "forge:entity_motionX") as? Double ?: 0.0
            val motionY = MappingContext.getFieldValue(player, "forge:entity_motionY") as? Double ?: 0.0
            val motionZ = MappingContext.getFieldValue(player, "forge:entity_motionZ") as? Double ?: 0.0

            val config = buildConfig()
            // Target sprint speed after the keep fraction (0.286 m/tick = 1.8.9 sprint base).
            val target = config.sprintBaseSpeed * config.horizontalKeep
            val restored = KeepSprintStrategy.restoreToTargetSpeed(motionX, motionY, motionZ, target)
                ?: return // stationary, or already at/above target — nothing to restore

            activeKeepFactor = config.horizontalKeep / KeepSprintStrategy.VANILLA_ATTACK_SLOWDOWN

            MappingContext.getField("forge:entity_motionX")?.setDouble(player, restored.x)
            MappingContext.getField("forge:entity_motionZ")?.setDouble(player, restored.z)
        } catch (_: Exception) {
            // Never crash the render loop.
        }
    }
}
