package io.doppel.core.strategy.velocity

import io.doppel.core.model.VelocityContext
import io.doppel.core.strategy.Strategy
import io.doppel.core.strategy.StrategyContext

/**
 * Strategy for processing incoming velocity packets.
 *
 * Consumes [VelocityContext] (extracted by the adapter) and produces
 * a [VelocityResult] that the adapter maps to `PlatformCommand`.
 *
 * The strategy itself contains zero platform knowledge — it only
 * decides *what* to do with the velocity, not *how* to apply it.
 */
interface VelocityStrategy : Strategy<VelocityConfig, VelocityStrategy.State, VelocityResult> {

    /**
     * Process the delay queue and release expired packets.
     * Default: empty list (no delay support). Overridden by Legit/Delay strategies.
     *
     * @param config current configuration snapshot.
     * @param state mutable strategy state.
     * @param currentTick the current server/client tick.
     * @return list of results for packets ready to be released.
     */
    fun pumpDelayed(config: VelocityConfig, state: State, currentTick: Int): List<VelocityResult> {
        return emptyList()
    }

    /**
     * Mutable per-session state for velocity processing.
     *
     * Implementations may extend this to add mode-specific fields
     * (e.g. click-mode hurt-time tracking).
     */
    open class State : StrategyContext {
        /**
         * Delayed packets awaiting release.
         * Each entry holds the original context and the tick at which it should be released.
         */
        data class DelayedEntry(
            val context: VelocityContext,
            val releaseTick: Int
        )

        val delayQueue: MutableList<DelayedEntry> = mutableListOf()
        var tickCounter: Int = 0

        // ── SPRINT mode (SprintVelocityStrategy) ──
        /** Ticks left in the armed brace window; 0 = not bracing. */
        var braceTicksLeft: Int = 0
        /** Knocked-back source direction the window was armed with. */
        var braceSourceX: Double = 0.0
        var braceSourceZ: Double = 0.0
        /** Gate state carried across hits: did the previous hit brace? */
        var braceLastPassed: Boolean = false

        override fun reset() {
            delayQueue.clear()
            tickCounter = 0
            braceTicksLeft = 0
            braceSourceX = 0.0
            braceSourceZ = 0.0
            braceLastPassed = false
        }
    }
}
