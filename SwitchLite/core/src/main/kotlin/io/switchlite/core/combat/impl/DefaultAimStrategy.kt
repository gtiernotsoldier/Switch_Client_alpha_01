package io.switchlite.core.combat.impl

import io.switchlite.core.combat.model.PlayerState
import io.switchlite.core.combat.model.TargetState
import io.switchlite.core.combat.strategy.AimStrategy
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.noise.NoiseProvider
import io.switchlite.core.option.AimConfig
import io.switchlite.core.option.AimMode
import io.switchlite.core.util.RotationMath
import io.switchlite.core.util.Vec2

class DefaultAimStrategy(
    private val conditionChecker: ConditionChecker,
    private val noiseProvider: NoiseProvider
) : AimStrategy {

    override fun calculateAimRotation(
        currentRotation: Vec2,
        player: PlayerState,
        target: TargetState?,
        config: AimConfig
    ): Vec2 {
        // 1. Safety checks and trigger conditions
        if (!config.enabled) return currentRotation
        if (target == null) return currentRotation
        if (!conditionChecker.check(config.triggerOptions, player, target)) return currentRotation

        // 2. Range check
        val distance = player.position.distanceTo(target.position)
        if (distance < config.range.start || distance > config.range.endInclusive) {
            return currentRotation
        }

        // 3. Target point calculation (Legit vs Normal mode)
        val targetPoint = if (config.mode == AimMode.LEGIT) {
            // Legit: Only correct when crosshair is outside hitbox
            if (RotationMath.isInsideHitbox(currentRotation, target.hitboxYaw)) {
                return currentRotation // Inside box, no correction
            }
            RotationMath.getClosestBoxEdge(currentRotation, target.hitboxYaw)
        } else {
            // Normal: Lock to configured point
            RotationMath.calculateTargetPoint(target.hitboxYaw, config.targetSelection)
        }

        // 4. Calculate rotation difference and FOV check
        val rotationDiff = RotationMath.calculateDifference(currentRotation, targetPoint)
        if (!RotationMath.isWithinFov(rotationDiff, config.horizontalFov, config.verticalFov)) {
            return currentRotation // Outside FOV, don't lock
        }

        // 5. Smooth interpolation (AimSpeed)
        var smoothedRotation = RotationMath.interpolate(currentRotation, rotationDiff, config.smoothness)

        // 6. Noise injection (simulate human hand tremor)
        smoothedRotation = noiseProvider.apply(smoothedRotation, config.noiseIntensity)

        return smoothedRotation
    }
}
