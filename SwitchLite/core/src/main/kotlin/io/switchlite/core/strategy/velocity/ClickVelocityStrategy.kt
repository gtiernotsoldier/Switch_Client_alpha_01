package io.switchlite.core.strategy.velocity

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.VelocityContext
import io.switchlite.core.option.RandomRange
import io.switchlite.core.util.Vec3

/**
 * CLICK mode velocity strategy ("Liquid" algorithm).
 *
 * Instead of modifying the velocity vector, this strategy detects
 * a specific [hurtTime] value and issues a click burst on the target.
 * This exploits the fact that hitting an opponent during their
 * hurt-time window effectively cancels their knockback on
 * many server implementations.
 *
 * Condition pipeline:
 * 1. Unified condition check → Pass if failed.
 * 2. Null target → Pass.
 * 3. hurtTime must exactly equal [VelocityConfig.hurtTimeToClick].
 * 4. (Optional) Player must be facing the target within [VelocityConfig.maxAngleDifference].
 * 5. Target must be within [VelocityConfig.clickRange].
 * 6. Roll click count → ClickBurst.
 */
class ClickVelocityStrategy : VelocityStrategy {

    override fun execute(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        input: Any
    ): VelocityResult {
        require(input is VelocityContext) { "ClickVelocityStrategy expects VelocityContext" }
        return processClick(config, input)
    }

    internal fun processClick(config: VelocityConfig, ctx: VelocityContext): VelocityResult {
        val player = ctx.player
        val target = ctx.target

        // 1. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return VelocityResult.Pass(ctx.originalMotion)
        }

        // 2. Target required
        if (target == null) return VelocityResult.Pass(ctx.originalMotion)

        // 3. Hurt-time match
        if (player.hurtTime != config.hurtTimeToClick) {
            return VelocityResult.Pass(ctx.originalMotion)
        }

        // 4. Facing check
        if (config.whenFacingEnemyOnly && !player.isLookingAtTarget) {
            return VelocityResult.Pass(ctx.originalMotion)
        }

        // 5. Distance check
        val distance = player.position.distanceTo(target.position)
        if (distance > config.clickRange) {
            return VelocityResult.Pass(ctx.originalMotion)
        }

        // 6. Roll and emit
        val clicks = RandomRange.sampleInt(config.clickBurstMin, config.clickBurstMax)
        return VelocityResult.ClickBurst(targetEntityId = target.id, times = clicks)
    }
}
