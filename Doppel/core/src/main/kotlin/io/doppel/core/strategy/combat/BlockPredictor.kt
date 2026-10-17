package io.doppel.core.strategy.combat

import kotlin.random.Random

/**
 * Block timing for the defender side — the algorithm behind AutoBlock's Predict mode.
 *
 * Two layers, deliberately separate, because only one of them is arithmetic:
 *
 * **A. The damage lock (deterministic).** In 1.8 the server discards a hit while the victim's
 * `hurtResistantTime` is above half of `maxHurtResistantTime` (20 → the first 10 ticks after a
 * hit). Nothing makes a hit land there, and the victim can still hit back, so the only useful
 * action inside that window is to hold the guard. This is a comparison, not a guess.
 *
 * The direction matters. `hurtResistantTime` counts DOWN from `maxHurtResistantTime`, so the lock
 * is the HIGH half (`> max/2`, i.e. 11..20 right after a hit) and the open window is
 * `<= max/2` (0..10). Getting that backwards — which is what feeding `CombatTrigger` PRE did, since
 * PRE fires on the 1..maxHurtTime span — blocks precisely when a hit would have landed and stands
 * exposed precisely when it would not.
 *
 * **B. The attack clock (probabilistic).** When the window is open we would rather be swinging
 * than blocking, unless the opponent is about to hit back. [State] learns the opponent's attack
 * interval from hits we took (an exponential moving average of the gaps) and blocks when the next
 * attack falls due, within [Config.predictWindowTicks].
 *
 * Layer B is an estimator with free parameters and its defaults are placeholders — calibrating them
 * needs a recording of real opponent attack rhythms, the same gap the rest of this codebase has for
 * human baselines. Layer A has no such gap: it is the vanilla rule.
 *
 * Both layers only *decide*. This object touches no Minecraft type, no EventBridge and no input.
 */
object BlockPredictor {

    /** 1.8 `EntityLivingBase.maxHurtResistantTime`. */
    const val DEFAULT_MAX_HURT = 20

    /**
     * Ticks to subtract from the target's i-frame counter before comparing.
     *
     * The client holds a delayed copy of that counter, so a target reading `h` on our side is
     * closer to `h - rtt` on the server. Ignoring it makes the lock look ~one RTT longer than it is
     * and the guard comes off late. This is also why HitSelect's `RetimeAt` defaults to 3 rather
     * than the nominal 10.
     */
    const val DEFAULT_RTT_TICKS = 2

    /** Release this many ticks before the lock lifts, so the swing is armed when it does. */
    const val DEFAULT_LEAD_TICKS = 2

    /** How close to the predicted attack a block may fire, in ticks. */
    const val DEFAULT_PREDICT_WINDOW = 3

    /** Probability that a due prediction actually holds the guard (0-100). */
    const val DEFAULT_PREDICT_CHANCE = 50

    /** EMA smoothing for the interval estimate; lower adapts more slowly. */
    const val DEFAULT_INTERVAL_ALPHA = 0.35

    /** Interval assumed until the first measurement lands. */
    const val DEFAULT_SEED_INTERVAL = 12.0

    /** Which layer produced the decision — surfaced for the HUD and for tests. */
    enum class Reason {
        /** Layer A: the target is damage-locked, a hit cannot land. Hold. */
        LOCKED,

        /** Layer B: a hit could land, but the opponent's next attack is due. Hold anyway. */
        PREDICTED_ATTACK,

        /** Neither: the window is open and no attack is expected — release and swing. */
        OPEN
    }

    data class Decision(
        val block: Boolean,
        val reason: Reason,
        /**
         * The target's i-frame counter after the round-trip correction — what layer A actually
         * compared. Reported so a HUD or a log can show *why* the decision came out this way
         * instead of only what it was.
         */
        val effectiveHurt: Int,
        /** Layer A alone: the target is damage-locked, so a hit cannot land. */
        val locked: Boolean = false,
        /** Layer B alone: the opponent's next attack is due within the prediction window. */
        val threat: Boolean = false
    )

    /** Per-module mutable state: the opponent's estimated attack rhythm. */
    class State {
        /** Tick of the last hit we took; -1 = none seen yet. */
        var lastOpponentHitTick: Int = -1

        /** EMA of the gaps between hits taken — the opponent's attack period, in ticks. */
        var intervalEma: Double = 0.0

        var intervalSamples: Int = 0

        fun reset() {
            lastOpponentHitTick = -1
            intervalEma = 0.0
            intervalSamples = 0
        }
    }

    data class Config(
        /** The victim's maxHurtResistantTime (1.8: 20). */
        val maxHurtResistantTime: Int = DEFAULT_MAX_HURT,
        val rttTicks: Int = DEFAULT_RTT_TICKS,
        val leadTicks: Int = DEFAULT_LEAD_TICKS,
        val predictAttacks: Boolean = true,
        val predictWindowTicks: Int = DEFAULT_PREDICT_WINDOW,
        val predictChance: Int = DEFAULT_PREDICT_CHANCE,
        val intervalAlpha: Double = DEFAULT_INTERVAL_ALPHA,
        val seedIntervalTicks: Double = DEFAULT_SEED_INTERVAL
    )

    /**
     * The i-frame value above which the server discards a hit.
     *
     * 1.8 `EntityLivingBase.attackEntityFrom` applies damage only while
     * `hurtResistantTime <= maxHurtResistantTime / 2`; above that it keeps the larger incoming
     * damage value and returns without applying anything.
     */
    fun lockThreshold(maxHurtResistantTime: Int): Int =
        (if (maxHurtResistantTime > 0) maxHurtResistantTime else DEFAULT_MAX_HURT) / 2

    /** True while a hit from us would be discarded by the server. */
    fun damageLocked(hurtResistantTime: Int, config: Config): Boolean {
        val effective = (hurtResistantTime - config.rttTicks).coerceAtLeast(0)
        return effective > lockThreshold(config.maxHurtResistantTime) + config.leadTicks
    }

    /**
     * One tick of the decision.
     *
     * @param targetHurtResistantTime target's i-frame counter, or null when there is no target.
     * @param currentTick monotonic tick index; only differences matter.
     * @param opponentJustHit true on the tick our own i-frame counter rose (we were hit).
     */
    fun evaluate(
        targetHurtResistantTime: Int?,
        currentTick: Int,
        opponentJustHit: Boolean,
        state: State,
        config: Config,
        rng: Random
    ): Decision {
        observeOpponent(currentTick, opponentJustHit, state, config)

        if (targetHurtResistantTime == null) {
            return Decision(false, Reason.OPEN, 0)
        }

        val effective = (targetHurtResistantTime - config.rttTicks).coerceAtLeast(0)

        // A — arithmetic. Inside the lock our hit is discarded whatever we do, and the target can
        // still hit us, so holding is strictly better than swinging.
        val locked = effective > lockThreshold(config.maxHurtResistantTime) + config.leadTicks

        // B — an estimate. Outside the lock we would rather swing, unless one is coming back.
        // Reported as its own flag rather than folded into the verdict: Adaptive mode weighs the two
        // layers separately, because they do not deserve equal trust (see [BlockArbiter]).
        val threat = !locked &&
            config.predictAttacks &&
            attackDue(currentTick, state, config) &&
            (config.predictChance >= 100 || rng.nextInt(100) < config.predictChance)

        return Decision(
            block = locked || threat,
            reason = when {
                locked -> Reason.LOCKED
                threat -> Reason.PREDICTED_ATTACK
                else -> Reason.OPEN
            },
            effectiveHurt = effective,
            locked = locked,
            threat = threat
        )
    }

    /** Record a hit we took and fold the gap since the previous one into the interval estimate. */
    private fun observeOpponent(currentTick: Int, justHit: Boolean, state: State, config: Config) {
        if (!justHit) return
        val previous = state.lastOpponentHitTick
        if (previous >= 0) {
            val gap = (currentTick - previous).toDouble()
            if (gap > 0) {
                // The first measured gap seeds the estimate; later ones are smoothed into it.
                state.intervalEma =
                    if (state.intervalSamples == 0) gap
                    else state.intervalEma + config.intervalAlpha * (gap - state.intervalEma)
                state.intervalSamples++
            }
        } else {
            // First hit ever: we have a timestamp but no period yet. The seed is only a placeholder
            // (it keeps the HUD readable); attackDue() refuses to predict until a real gap lands.
            state.intervalEma = config.seedIntervalTicks
        }
        state.lastOpponentHitTick = currentTick
    }

    /** True when the opponent's next attack is due within the prediction window. */
    private fun attackDue(currentTick: Int, state: State, config: Config): Boolean {
        val last = state.lastOpponentHitTick
        if (last < 0 || state.intervalSamples <= 0) return false
        val since = (currentTick - last).toDouble()
        val due = state.intervalEma - since
        // Bounded on BOTH sides. Above +window is too early to commit to a block; below -window the
        // predicted moment has passed, so the estimate was wrong for this cycle and the guard should
        // come down rather than stay up indefinitely for an attack that never arrived. (Without the
        // lower bound the clock latches: `due` only grows more negative, so it stays "due".)
        return due <= config.predictWindowTicks && due >= -config.predictWindowTicks
    }
}
