package io.switchlite.core.strategy.velocity

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.VelocityContext

/**
 * DELAY mode velocity strategy.
 *
 * Buffers the incoming velocity packet for [VelocityConfig.delayTicks] +
 * [VelocityConfig.delayMs]/50 ticks, then releases it with LEGIT-style
 * scaling applied at release time.
 *
 * On each tick, the adapter should call [pumpDelayed] to retrieve
 * any packets whose delay has expired.
 *
 * This strategy reuses [LegitVelocityStrategy] for the actual
 * scaling computation at release time, keeping the scaling logic
 * in one place.
 */
class DelayVelocityStrategy(
    private val legitDelegate: LegitVelocityStrategy = LegitVelocityStrategy()
) : VelocityStrategy {

    override fun execute(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        input: Any
    ): VelocityResult {
        require(input is VelocityContext) { "DelayVelocityStrategy expects VelocityContext" }
        return processDelay(config, state, input)
    }

    internal fun processDelay(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        ctx: VelocityContext
    ): VelocityResult {
        val player = ctx.player
        val target = ctx.target

        // 1. Condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return VelocityResult.Pass(ctx.originalMotion)
        }

        // 2. Enqueue for delayed release
        val totalDelayTicks = config.delayTicks + (config.delayMs / 50)
        state.delayQueue.add(
            VelocityStrategy.State.DelayedEntry(
                context = ctx,
                releaseTick = state.tickCounter + totalDelayTicks
            )
        )

        return VelocityResult.Cancel(ctx.packetHandle)
    }

    /**
     * Process the delay queue and release expired packets with LEGIT scaling.
     *
     * @param config the current configuration snapshot.
     * @param state mutable strategy state.
     * @param currentTick the current server/client tick.
     * @return list of results for packets ready to be released.
     */
    fun pumpDelayed(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        currentTick: Int
    ): List<VelocityResult> {
        state.tickCounter = currentTick
        return legitDelegate.pumpDelayed(config, state, currentTick)
    }
}
