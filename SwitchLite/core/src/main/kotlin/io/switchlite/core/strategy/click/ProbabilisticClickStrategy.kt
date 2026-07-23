package io.switchlite.core.strategy.click

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState

/**
 * Default click strategy with probabilistic CPS and distance adjustment.
 *
 * Processing pipeline per tick:
 * 1. Block-hit prevention → Skip if mining and [ClickConfig.disableOnBlock] is set.
 * 2. Pending second click (DOUBLE mode) → Click immediately.
 * 3. Null-target or condition fail → Skip.
 * 4. Target change detection → update [ClickStrategy.State.lastTargetId].
 * 5. Compute effective CPS based on operating mode.
 * 6. Convert CPS to per-tick probability and roll.
 * 7. If hit: Click (and in DOUBLE mode, set pending flag for next tick).
 */
class ProbabilisticClickStrategy : ClickStrategy {

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
        target: TargetState?
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

        // 3. Target + condition check
        if (target == null || !ConditionChecker.check(config.triggerOptions, player, target)) {
            return ClickResult.Skip
        }

        // 4. Target change detection
        if (target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
        }

        // 5. Effective CPS
        val lo = config.minCps.coerceAtMost(config.maxCps)
        val hi = config.maxCps.coerceAtLeast(config.minCps)
        val effectiveCps = when (config.mode) {
            ClickOperatingMode.NORMAL -> sampleCpsInRange(lo, hi)
            ClickOperatingMode.LEGIT -> {
                val base = sampleCpsInRange(lo, hi)
                adjustCpsByDistance(base, lo, hi, target.distance)
            }
        }.coerceAtLeast(1)

        // 6. Per-tick probability roll
        val clickChance = effectiveCps / 20.0
        if (NoiseProvider.nextUniform(0f, 1f) >= clickChance) {
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
    private fun sampleCpsInRange(lo: Int, hi: Int): Int {
        if (lo == hi) return lo
        return lo + (NoiseProvider.nextUniform(0f, 1f) * (hi - lo + 1)).toInt().coerceIn(0, hi - lo)
    }

    /**
     * Adjust CPS based on target distance for LEGIT mode.
     * - Distance > 6 blocks: reduce CPS by 2-5.
     * - Distance < 3 blocks: increase CPS by 3-5.
     * - Otherwise: no change.
     */
    private fun adjustCpsByDistance(baseCps: Int, lo: Int, hi: Int, distance: Float): Int {
        val adjusted = when {
            distance > 6.0f -> {
                val reduction = NoiseProvider.nextUniform(2f, 5f).toInt()
                baseCps - reduction
            }
            distance < 3.0f -> {
                val boost = NoiseProvider.nextUniform(3f, 5f).toInt()
                baseCps + boost
            }
            else -> baseCps
        }
        return adjusted.coerceIn(lo, hi)
    }
}
