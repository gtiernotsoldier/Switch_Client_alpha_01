package io.switchlite.core.condition

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.TriggerOptions
import kotlin.math.*

/**
 * Unified condition checker for all trigger options.
 * Compiles TriggerOptions into optimized check functions.
 */
object ConditionChecker {

    /**
     * Maximum angle (degrees) from the player's look direction to the target's
     * center that still counts as "looking at target".
     * Aiming at the head vs feet of a 1.8-block-tall mob at 4 blocks distance
     * is ~13°. 30° provides comfortable margin.
     */
    private const val LOOK_ANGLE_THRESHOLD_DEG = 30.0

    /**
     * Check if all conditions are met for activation.
     * When target is null and target-based conditions are enabled, returns true (don't block).
     */
    fun check(options: TriggerOptions, player: PlayerState, target: TargetState?): Boolean {
        // Ground/Air checks
        if (options.onlyGround && !player.onGround) return false
        if (options.onlyAir && player.onGround) return false
        if (options.disabledInAir && !player.onGround) return false

        // Movement checks
        if (options.onlyMove && !player.isMoving) return false
        if (options.onlyMoveForward && !player.isMovingForward) return false
        if (options.onlyMoveBackward) return false // TODO: Implement backward detection
        if (options.onlyStrafe) return false // TODO: Implement strafe detection

        // Target-based checks — skip when target is null
        if (target != null) {
            if (options.onlyWhenTargetGoesBack && !target.isMovingBackward) return false
            if (options.onlyWhenTargetApproaches) return false // TODO: Implement approach detection

            // Distance checks
            if (target.distance < options.minDistance) return false
            if (target.distance > options.maxDistance) return false
        }

        // Look direction check (onLook = crosshair alignment, same logic as onlyCurrentView)
        if (options.onLook) {
            if (target == null || !isLookingAt(player, target)) return false
        }

        // onlyCurrentView check — angle-based, no platform raytrace needed
        if (options.onlyCurrentView && target != null && !isLookingAt(player, target)) return false

        // onlyOnClick check — player must be holding the attack key
        if (options.onlyOnClick && !player.isAttackKeyDown) return false

        // disableOnMine check
        if (options.disableOnMine && player.isMining) return false

        // Chance check
        if (options.chance < 100) {
            val random = kotlin.random.Random.nextInt(100)
            if (random >= options.chance) return false
        }

        // Delay checks handled externally via tick counters

        return true
    }

    /**
     * Determine whether the player is looking at the target entity.
     *
     * Method B (angle-based): computes the horizontal angle between the
     * player's look direction (yaw) and the vector from player eye to
     * target center. If the angle is within [LOOK_ANGLE_THRESHOLD_DEG],
     * returns true.
     *
     * This avoids any platform raytrace API — pure math on already-available
     * player.rotation and target.position.
     */
    private fun isLookingAt(player: PlayerState, target: TargetState): Boolean {
        val dx = target.position.x - player.position.x
        val dz = target.position.z - player.position.z
        if (dx == 0.0 && dz == 0.0) return true // on top of target

        // Yaw to target in degrees (MC convention: -180 to 180, south = 0)
        val yawToTarget = (atan2(-dx, dz) * (180.0 / PI)).toFloat()

        // Normalize player yaw to -180..180
        var playerYaw = player.rotation.yaw % 360f
        if (playerYaw > 180f) playerYaw -= 360f
        if (playerYaw < -180f) playerYaw += 360f

        // Absolute difference
        var diff = (playerYaw - yawToTarget) % 360f
        if (diff > 180f) diff = 360f - diff
        if (diff < -180f) diff = -360f - diff
        val angleDeg = abs(diff).toDouble()

        return angleDeg <= LOOK_ANGLE_THRESHOLD_DEG
    }

    /**
     * Compile options into a reusable check function.
     * For performance optimization (fingerprint caching).
     */
    fun compile(options: TriggerOptions): (PlayerState, TargetState?) -> Boolean {
        return { player, target -> check(options, player, target) }
    }
}
