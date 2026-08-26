package io.switchlite.core.strategy.velocity

import io.switchlite.core.algorithm.VectorOperations
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.VelocityContext
import io.switchlite.core.option.RandomRange
import io.switchlite.core.util.Vec3

/**
 * Default LEGIT mode velocity strategy.
 *
 * Behaviour per invocation:
 * 1. Run unified condition check → Pass if failed.
 * 2. Roll probability check → Pass if missed.
 * 3. Sample h/v factors from configured ranges.
 * 4. Scale the motion vector → Modify.
 *
 * This is the foundational velocity reduction — DELAY mode
 * delegates to this same logic after buffering the packet.
 */
class LegitVelocityStrategy : VelocityStrategy {

    override fun execute(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        input: Any
    ): VelocityResult {
        require(input is VelocityContext) { "LegitVelocityStrategy expects VelocityContext" }
        return processLegit(config, state, input)
    }

    // Visible-for-testing entry point
    @Suppress("UNUSED_PARAMETER")
    internal fun processLegit(config: VelocityConfig, state: VelocityStrategy.State, ctx: VelocityContext): VelocityResult {
        val player = ctx.player
        val target = ctx.target
        val original = ctx.originalMotion

        // 1. Unified condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target)) {
            return VelocityResult.Pass(original)
        }

        // 2. Probability check
        if (config.probability < 100) {
            val roll = (Math.random() * 100).toInt()
            if (roll >= config.probability) return VelocityResult.Pass(original)
        }

        // 3. Sample and scale
        val h = RandomRange.sample(config.horizontalMin, config.horizontalMax)
        val v = RandomRange.sample(config.verticalMin, config.verticalMax)
        val reduced = VectorOperations.scale(original, h, v, h)

        return VelocityResult.Modify(reduced)
    }

    /**
     * Process queued delayed entries and release those whose time has come.
     * The adapter should call this on every tick and map returned results to
     * [io.switchlite.core.model.PlatformCommand]s.
     *
     * @return list of results for packets that are ready to be released.
     */
    override fun pumpDelayed(config: VelocityConfig, state: VelocityStrategy.State, currentTick: Int): List<VelocityResult> {
        state.tickCounter = currentTick
        val results = mutableListOf<VelocityResult>()
        val iterator = state.delayQueue.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTick >= entry.releaseTick) {
                // Re-process with LEGIT logic but skip delay check to avoid re-enqueuing.
                // We directly scale and emit.
                val h = RandomRange.sample(config.horizontalMin, config.horizontalMax)
                val v = RandomRange.sample(config.verticalMin, config.verticalMax)
                val reduced = VectorOperations.scale(entry.context.originalMotion, h, v, h)
                results.add(VelocityResult.Modify(reduced))
                iterator.remove()
            }
        }
        return results
    }
}
