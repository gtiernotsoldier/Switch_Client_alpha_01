package io.switchlite.core.strategy.velocity

import io.switchlite.core.model.VelocityContext
import io.switchlite.core.strategy.Strategy
import io.switchlite.core.strategy.StrategyContext

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

        override fun reset() {
            delayQueue.clear()
            tickCounter = 0
        }
    }
}
