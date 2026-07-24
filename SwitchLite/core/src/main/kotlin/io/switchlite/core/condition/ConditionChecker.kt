package io.switchlite.core.condition

import io.switchlite.core.logging.CoreLogger
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.option.TriggerOptions
import kotlin.math.*

/**
 * Unified condition checker for all trigger options.
 * All modules share this engine — no repetitive if-statements in individual modules.
 *
 * Constitution compliance:
 * - Debuggability: logs which condition blocked activation (DEBUG level).
 * - Safety: lookAngleThreshold is configurable via TriggerOptions (per anti-cheat pack).
 * - Pure math: zero platform dependencies.
 */
object ConditionChecker {

    /**
     * Check if all conditions are met for activation.
     * When target is null and target-based conditions are enabled, they are skipped (don't block).
     *
     * @return true if the module should activate this tick.
     */
    fun check(options: TriggerOptions, player: PlayerState, target: TargetState?): Boolean {
        // Ground/Air checks
        if (options.onlyGround && !player.onGround) {
            CoreLogger.debug("Condition blocked: onlyGround (player in air)")
            return false
        }
        if (options.onlyAir && player.onGround) {
            CoreLogger.debug("Condition blocked: onlyAir (player on ground)")
            return false
        }
        if (options.disabledInAir && !player.onGround) {
            CoreLogger.debug("Condition blocked: disabledInAir")
            return false
        }

        // Movement checks
        if (options.onlyMove && !player.isMoving) {
            CoreLogger.debug("Condition blocked: onlyMove (player stationary)")
            return false
        }
        if (options.onlyMoveForward && !player.isMovingForward) {
            CoreLogger.debug("Condition blocked: onlyMoveForward")
            return false
        }
        if (options.onlyMoveBackward && !isMovingBackward(player)) {
            CoreLogger.debug("Condition blocked: onlyMoveBackward")
            return false
        }
        if (options.onlyStrafe && !isStrafing(player)) {
            CoreLogger.debug("Condition blocked: onlyStrafe")
            return false
        }

        // Target-based checks — skip when target is null
        if (target != null) {
            if (options.onlyWhenTargetGoesBack && !target.isMovingBackward) {
                CoreLogger.debug("Condition blocked: onlyWhenTargetGoesBack")
                return false
            }
            if (options.onlyWhenTargetApproaches && !target.isMovingTowardsPlayer) {
                CoreLogger.debug("Condition blocked: onlyWhenTargetApproaches")
                return false
            }

            // Distance checks
            if (target.distance < options.minDistance) {
                CoreLogger.debug("Condition blocked: minDistance (${target.distance} < ${options.minDistance})")
                return false
            }
            if (target.distance > options.maxDistance) {
                CoreLogger.debug("Condition blocked: maxDistance (${target.distance} > ${options.maxDistance})")
                return false
            }
        }

        // Look direction check (onLook = crosshair alignment)
        if (options.onLook) {
            if (target == null || !isLookingAt(player, target, options.lookAngleThreshold)) {
                CoreLogger.debug("Condition blocked: onLook (not looking at target)")
                return false
            }
        }

        // onlyCurrentView check — angle-based, no platform raytrace needed
        if (options.onlyCurrentView && target != null && !isLookingAt(player, target, options.lookAngleThreshold)) {
            CoreLogger.debug("Condition blocked: onlyCurrentView")
            return false
        }

        // onlyOnClick check — player must be holding the attack key
        if (options.onlyOnClick && !player.isAttackKeyDown) {
            CoreLogger.debug("Condition blocked: onlyOnClick (attack key not held)")
            return false
        }

        // disableOnMine check
        if (options.disableOnMine && player.isMining) {
            CoreLogger.debug("Condition blocked: disableOnMine")
            return false
        }

        // Chance check
        if (options.chance < 100) {
            val roll = kotlin.random.Random.nextInt(100)
            if (roll >= options.chance) {
                CoreLogger.debug("Condition blocked: chance ($roll >= ${options.chance})")
                return false
            }
        }

        // Delay checks: handled externally by each module's tick counter.
        // TriggerOptions.delayTicks / delayMs are read by the module adapter,
        // which skips activation for the configured number of ticks after enable.

        return true
    }

    /**
     * Determine whether the player is looking at the target entity.
     *
     * Angle-based method: computes the horizontal angle between the player's
     * look direction (yaw) and the vector from player to target center.
     * Pure math — no platform raytrace API needed.
     *
     * @param thresholdDeg configurable angle threshold from TriggerOptions.
     */
    private fun isLookingAt(player: PlayerState, target: TargetState, thresholdDeg: Float): Boolean {
        val dx = target.position.x - player.position.x
        val dz = target.position.z - player.position.z
        if (dx == 0.0 && dz == 0.0) return true // on top of target

        // Yaw to target in degrees (MC convention: yaw=0 faces south/+Z, yaw=90 faces west/-X)
        val yawToTarget = (atan2(-dx, dz) * (180.0 / PI)).toFloat()

        // Normalize both to -180..180 and compute absolute angular difference
        val playerYaw = normalizeAngle(player.rotation.yaw)
        val angleDeg = abs(normalizeAngle(playerYaw - yawToTarget))

        return angleDeg <= thresholdDeg
    }

    /**
     * Detect backward movement: player's horizontal motion opposes their look direction.
     * Dot product of motion vector and forward direction vector < 0 → moving backward.
     */
    private fun isMovingBackward(player: PlayerState): Boolean {
        val speed = sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ)
        if (speed < 0.01) return false

        val yawRad = (player.rotation.yaw * PI / 180.0)
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)

        val dot = player.motionX * forwardX + player.motionZ * forwardZ
        return dot < -0.01
    }

    /**
     * Detect strafing: player has significant lateral motion relative to look direction.
     * Decomposes motion into forward and lateral components; if lateral dominates, strafing.
     */
    private fun isStrafing(player: PlayerState): Boolean {
        val speed = sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ)
        if (speed < 0.01) return false

        val yawRad = (player.rotation.yaw * PI / 180.0)
        val forwardX = -sin(yawRad)
        val forwardZ = cos(yawRad)

        // Forward component (dot product)
        val forward = player.motionX * forwardX + player.motionZ * forwardZ
        // Lateral component (2D cross product magnitude)
        val lateral = player.motionX * forwardZ - player.motionZ * forwardX

        return abs(lateral) > abs(forward) && abs(lateral) > 0.01
    }

    /**
     * Normalize an angle to the range [-180, 180].
     */
    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    /**
     * Compile options into a reusable check function.
     * The returned lambda captures the options instance — modules can cache it
     * and call it per-tick without re-reading config fields.
     */
    fun compile(options: TriggerOptions): (PlayerState, TargetState?) -> Boolean {
        return { player, target -> check(options, player, target) }
    }
}
