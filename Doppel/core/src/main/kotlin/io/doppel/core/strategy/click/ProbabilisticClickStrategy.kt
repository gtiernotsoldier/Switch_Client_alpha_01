package io.doppel.core.strategy.click

import io.doppel.core.condition.ConditionChecker
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.option.ClickMode
import io.doppel.contract.rng.DefaultRandomSource
import io.doppel.contract.rng.RandomSource
import io.doppel.contract.strategy.click.ClickConfig
import io.doppel.contract.strategy.click.ClickInput
import io.doppel.contract.strategy.click.ClickOperatingMode
import io.doppel.contract.strategy.click.ClickResult
import io.doppel.contract.strategy.click.ClickStrategy

/**
 * Default click strategy with probabilistic CPS and distance adjustment.
 *
 * Processing pipeline per tick:
 * 1. Block-hit prevention → Skip if mining and [ClickConfig.disableOnBlock] is set.
 * 2. Pending second click (DOUBLE mode) → Click immediately.
 * 3. Condition fail → Skip (blind: target may be null, ConditionChecker skips
 *    target-based conditions when null, matching CooldownClickStrategy).
 * 4. Target change detection → update [ClickStrategy.State.lastTargetId].
 * 5. Compute effective CPS based on operating mode.
 * 6. Convert CPS to per-tick probability and roll.
 * 7. If hit: Click (and in DOUBLE mode, set pending flag for next tick).
 */
class ProbabilisticClickStrategy : ClickStrategy<ClickConfig, ClickStrategy.State> {

    override fun execute(
        config: ClickConfig,
        state: ClickStrategy.State,
        input: Any
    ): ClickResult {
        require(input is ClickInput) { "ProbabilisticClickStrategy expects ClickInput" }
        return processTick(config, state, input.player, input.target)
    }

    // ---- Visible for testing ----

    internal fun processTick(
        config: ClickConfig,
        state: ClickStrategy.State,
        player: PlayerState,
        target: TargetState?,
        random: io.doppel.contract.rng.RandomSource = io.doppel.contract.rng.DefaultRandomSource
    ): ClickResult {
        // 1. Block-hit prevention
        if (config.disableOnBlock && player.isMining) {
            return ClickResult.Skip
        }

        // 2. Pending second click from DOUBLE mode
        if (state.pendingSecondClick) {
            state.pendingSecondClick = false
            return ClickResult.Click
        }

        // 3. Condition check (blind: target may be null — ConditionChecker skips
        //    target-based conditions when null, matching CooldownClickStrategy)
        if (!ConditionChecker.check(config.triggerOptions, player, target, random)) {
            return ClickResult.Skip
        }

        // 4. Target change detection
        if (target != null && target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
        }

        // 5. Effective CPS
        val lo = config.minCps.coerceAtMost(config.maxCps)
        val hi = config.maxCps.coerceAtLeast(config.minCps)
        val effectiveCps = when (config.mode) {
            ClickOperatingMode.NORMAL -> sampleCpsInRange(lo, hi, random)
            ClickOperatingMode.LEGIT -> {
                val base = sampleCpsInRange(lo, hi, random)
                // Blind mode: when there's no target there is no distance to adjust by.
                if (target != null) adjustCpsByDistance(base, lo, hi, target.distance, random) else base
            }
        }.coerceAtLeast(1)

        // 6. Per-tick probability roll
        val clickChance = effectiveCps / 20.0
        if (nextUniform(0f, 1f, random) >= clickChance) {
            return ClickResult.Skip
        }

        // 7. Emit click
        if (config.clickMode == ClickMode.DOUBLE) {
            state.pendingSecondClick = true
        }
        return ClickResult.Click
    }

    // ---- Helpers ----

    /**
     * Sample a CPS value uniformly within [lo, hi].
     */
    private fun sampleCpsInRange(lo: Int, hi: Int, random: io.doppel.contract.rng.RandomSource): Int {
        if (lo == hi) return lo
        return lo + (nextUniform(0f, 1f, random) * (hi - lo + 1)).toInt().coerceIn(0, hi - lo)
    }

    /** Same shape as `NoiseProvider.nextUniform`, routed through the injectable source. */
    private fun nextUniform(min: Float, max: Float, random: io.doppel.contract.rng.RandomSource): Float =
        min + random.nextFloat() * (max - min)

    /**
     * Adjust CPS based on target distance for LEGIT mode.
     * - Distance > 2.5 blocks: reduce CPS by 2-5.
     * - Distance < 1.5 blocks: increase CPS by 3-5.
     * - Otherwise: no change.
     */
    private fun adjustCpsByDistance(
        baseCps: Int,
        lo: Int,
        hi: Int,
        distance: Float,
        random: io.doppel.contract.rng.RandomSource
    ): Int {
        val adjusted = when {
            distance > 2.5f -> {
                val reduction = nextUniform(2f, 5f, random).toInt()
                baseCps - reduction
            }
            distance < 1.5f -> {
                val boost = nextUniform(3f, 5f, random).toInt()
                baseCps + boost
            }
            else -> baseCps
        }
        return adjusted.coerceIn(lo, hi)
    }
}
