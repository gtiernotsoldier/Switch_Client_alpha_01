package io.doppel.core.strategy.velocity

import io.doppel.core.algorithm.VectorOperations
import io.doppel.core.condition.ConditionChecker
import io.doppel.contract.rng.DefaultRandomSource
import io.doppel.contract.rng.RandomSource
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.model.PlatformCommand
import io.doppel.contract.strategy.velocity.VelocityConfig
import io.doppel.contract.strategy.velocity.VelocityResult
import io.doppel.contract.strategy.velocity.VelocityStrategy

/**
 * Default LEGIT mode velocity strategy.
 *
 * Behaviour per invocation:
 * 1. Run unified condition check → Pass if failed.
 * 2. Hit-frame filter → Pass for anything that is not an attack's knockback.
 * 3. Roll the gate → Pass if it says skip.
 * 4. Sample h/v factors from the configured ranges.
 * 5. Scale the motion vector → Modify.
 *
 * **The gate is one memoryless two-state chain, and it lives here rather than in the module.**
 * It used to be two independent dice: this strategy rolled `probability`, and the module rolled a
 * second, *cadence-shaped* gate on top of it (`NaturalMode` = EveryNth / Incremental). A cadence is
 * the one shape a run-length detector is built for — measured against the human sprint-reset
 * population, a fixed "every Nth hit" cycle scores D ≈ 0.17–0.30 and a rate-matched memoryless gate
 * scores ≈ 0.03. So the two dice became one chain with two numbers: P(reduce | reduced on the
 * previous hit) and P(reduce | skipped on the previous hit). Equal values are memoryless; unequal
 * values are what a real human's autocorrelation would look like, and that has to come from a
 * recording rather than from a guess.
 *
 * Note what this does *not* fix. The reduction is still a packet rewrite, so every hit the gate
 * lets through still opens with a first-tick position error of `r × 0.4` blocks. The gate buys the
 * distribution and rhythm layers, never the physics layer.
 *
 * @param rng the source for both dice. Defaults to unseeded randomness; the replay harness
 *   passes a [io.doppel.core.rng.DecisionRng] so the same seed reproduces the same sequence —
 *   without this, "same seed, same decisions" (PROTECTION.md 9.5.5) could not be checked at all.
 */
class LegitVelocityStrategy(
    private val rng: RandomSource = DefaultRandomSource
) : VelocityStrategy {

    override fun execute(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        input: Any
    ): VelocityResult {
        require(input is VelocityContext) { "LegitVelocityStrategy expects VelocityContext" }
        return processLegit(config, state, input)
    }

    // Visible-for-testing entry point
    internal fun processLegit(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        ctx: VelocityContext
    ): VelocityResult {
        val player = ctx.player
        val target = ctx.target
        val original = ctx.originalMotion

        // 1. Unified condition check
        if (!ConditionChecker.check(config.triggerOptions, player, target, rng)) {
            return VelocityResult.Pass(original)
        }

        // 2. Hit-frame filter — reduce on an attack's knockback (S12) and leave explosions (S27)
        //    alone. The S12 arriving IS the hit-frame signal, without reading hurtResistantTime.
        if (config.onlyOnHitFrame && !ctx.isKnockbackHit) {
            return VelocityResult.Pass(original)
        }

        // 3. The gate: one chain, two states, no cadence.
        val chance =
            if (state.reduceLastPassed) config.reduceGivenReduced else config.reduceGivenSkipped
        val passed = rng.nextInt(100) < chance
        state.reduceLastPassed = passed
        if (!passed) return VelocityResult.Pass(original)

        // 4. Sample and scale
        val h = rng.randomFloat(config.horizontalMin, config.horizontalMax)
        val v = rng.randomFloat(config.verticalMin, config.verticalMax)
        val reduced = VectorOperations.scale(original, h, v, h)

        return VelocityResult.Modify(reduced)
    }

    /**
     * Process queued delayed entries and release those whose time has come.
     * The adapter should call this on every tick and map returned results to
     * [io.doppel.contract.model.PlatformCommand]s.
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
                val h = rng.randomFloat(config.horizontalMin, config.horizontalMax)
                val v = rng.randomFloat(config.verticalMin, config.verticalMax)
                val reduced = VectorOperations.scale(entry.context.originalMotion, h, v, h)
                results.add(VelocityResult.Modify(reduced))
                iterator.remove()
            }
        }
        return results
    }
}
