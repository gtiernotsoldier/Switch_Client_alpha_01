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

        /** LEGIT mode: did the previous hit pass the gate? The chain's second state. */
        var reduceLastPassed: Boolean = false

        // ── SPRINT mode (SprintVelocityStrategy) ──
        /**
         * One armed brace window.
         *
         * Immutable and published as a single reference: [arm] runs on the packet (Netty) thread
         * while [SprintVelocityStrategy.braceKeys] runs on the tick thread, and three separate
         * plain fields let the tick thread read a new sourceX next to a stale sourceZ — a brace
         * pointing 90° off for one tick, which is worse than not bracing at all.
         */
        class BraceWindow(val sourceX: Double, val sourceZ: Double, val ticks: Int)

        /** The window the packet thread last armed; null = idle. */
        @Volatile var braceWindow: BraceWindow? = null

        /** Ticks left in the current window. Tick thread only. */
        var braceTicksLeft: Int = 0

        /** Which [braceWindow] object [braceTicksLeft] belongs to. Tick thread only. */
        internal var braceAdopted: BraceWindow? = null

        /** Gate state carried across hits: did the previous hit brace? */
        var braceLastPassed: Boolean = false

        override fun reset() {
            delayQueue.clear()
            tickCounter = 0
            reduceLastPassed = false
            braceWindow = null
            braceTicksLeft = 0
            braceAdopted = null
            braceLastPassed = false
        }
    }
}
