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
 * Default click strategy: a rhythm of scheduled inter-click intervals.
 *
 * Port of `core-rs/src/strategy/click.rs::probabilistic_tick`, bit for bit —
 * the draw order below is a cross-language contract, so any reordering here
 * desynchronises the replay, not just the statistics.
 *
 * Processing pipeline per tick:
 * 1. Block-hit prevention → Skip if mining and [ClickConfig.disableOnBlock]
 *    is set; the rhythm dies (a player busy mining is not mid-click-train).
 * 2. Pending second click (DOUBLE mode) → Click immediately.
 * 3. Condition fail → Skip; the rhythm dies and the next engagement
 *    restarts at the reaction floor.
 * 4. Target change detection → update [ClickStrategy.State.lastTargetId].
 * 5. Rhythm scheduler: an idle rhythm re-arms with a fresh interval (the
 *    reaction gap, floored at [RE_ENGAGE_MIN_TICKS]); a live one counts down
 *    and fires on zero. On fire: draw the next interval (tempo sample,
 *    distance-adjusted in LEGIT; AR(1) multiplicative jitter; rare pause).
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
        random: RandomSource = DefaultRandomSource
    ): ClickResult {
        // 1. Block-hit prevention. A player busy mining is not
        //    mid-click-train, so the rhythm dies here too, not just the click.
        if (config.disableOnBlock && player.isMining) {
            state.rhythmLive = false
            return ClickResult.Skip
        }

        // 2. Pending second click from DOUBLE mode: the pair completes
        //    regardless of where the rhythm scheduler is.
        if (state.pendingSecondClick) {
            state.pendingSecondClick = false
            return ClickResult.Click
        }

        // 3. Condition check (blind: target may be null — ConditionChecker
        //    skips target-based conditions when null, matching
        //    CooldownClickStrategy). The rhythm dies; the next engagement
        //    restarts from the reaction floor.
        if (!ConditionChecker.check(config.triggerOptions, player, target, random)) {
            state.rhythmLive = false
            return ClickResult.Skip
        }

        // 4. Target change detection
        if (target != null && target.entityId != state.lastTargetId) {
            state.lastTargetId = target.entityId
        }

        // 5. The rhythm scheduler. An idle rhythm re-arms with a fresh
        //    interval (the reaction gap, floored); a live one counts down
        //    and fires on zero.
        if (!state.rhythmLive) {
            val interval = drawInterval(config, state, target, random)
                .coerceAtLeast(RE_ENGAGE_MIN_TICKS)
            state.nextClickIn = interval - 1
            state.rhythmLive = true
            return ClickResult.Skip
        }
        if (state.nextClickIn > 0) {
            state.nextClickIn -= 1
            return ClickResult.Skip
        }

        // Due this tick: fire, then schedule the next interval from *next*
        // tick.
        val interval = drawInterval(config, state, target, random)
        state.nextClickIn = interval - 1
        if (config.clickMode == ClickMode.DOUBLE) {
            state.pendingSecondClick = true
        }
        return ClickResult.Click
    }

    // ---- Helpers ----

    /**
     * Draw the next inter-click interval in ticks: a tempo (CPS) sample, the
     * AR(1) multiplicative jitter, a rare pause. Bit-identical to
     * `draw_interval` in the Rust twin — same draw order, same Float
     * arithmetic, same rounding.
     */
    private fun drawInterval(
        config: ClickConfig,
        state: ClickStrategy.State,
        target: TargetState?,
        random: RandomSource
    ): Int {
        val lo = config.minCps.coerceAtMost(config.maxCps)
        val hi = config.maxCps.coerceAtLeast(config.minCps)
        var tempo = sampleCpsInRange(lo, hi, random)
        // Blind mode: when there's no target there is no distance to adjust
        // by — and no draw consumed, matching the Rust.
        if (config.mode == ClickOperatingMode.LEGIT && target != null) {
            tempo = adjustCpsByDistance(tempo, lo, hi, target.distance, random)
        }
        val tempoF = tempo.coerceAtLeast(1).toFloat()

        // AR(1) jitter around 1.0: bursts of short intervals, lulls of long
        // ones.
        val jitterTarget = JITTER_LO + JITTER_SPAN * random.nextFloat()
        state.jitter = JITTER_PERSIST * state.jitter + (1f - JITTER_PERSIST) * jitterTarget

        var interval = (20.0f / tempoF) * state.jitter
        if (random.nextFloat() < PAUSE_CHANCE) {
            interval *= PAUSE_MIN + PAUSE_SPAN * random.nextFloat()
        }
        return (interval + 0.5f).toInt().coerceAtLeast(1)
    }

    /**
     * Sample a CPS value uniformly within [lo, hi].
     */
    private fun sampleCpsInRange(lo: Int, hi: Int, random: RandomSource): Int {
        if (lo == hi) return lo
        return lo + (nextUniform(0f, 1f, random) * (hi - lo + 1)).toInt().coerceIn(0, hi - lo)
    }

    /** Same shape as `NoiseProvider.nextUniform`, routed through the injectable source. */
    private fun nextUniform(min: Float, max: Float, random: RandomSource): Float =
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
        random: RandomSource
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

    private companion object {
        /** Reaction floor in ticks (200 ms at 20 tps) — see the Rust twin. */
        const val RE_ENGAGE_MIN_TICKS = 4
        const val JITTER_PERSIST = 0.65f
        const val JITTER_LO = 0.8f
        const val JITTER_SPAN = 0.4f
        const val PAUSE_CHANCE = 0.05f
        const val PAUSE_MIN = 2.0f
        const val PAUSE_SPAN = 2.0f
    }
}
