package io.switchlite.core.strategy

/**
 * Marker interface for all core strategies.
 *
 * Strategies live in the core layer — they consume pure data models
 * (PlayerState, TargetState, CombatContext, etc.) and produce typed
 * result values. They have zero knowledge of Minecraft, adapters, or
 * platform commands.
 *
 * The adapter layer maps strategy results → PlatformCommand via
 * per-module glue code.
 *
 * Design principles:
 * 1. Stateless by default — all runtime state lives in [StrategyContext].
 * 2. Deterministic given the same inputs (except where noise is explicitly requested).
 * 3. Testable in pure JUnit without any Minecraft runtime.
 *
 * @param C the configuration type that parameterizes this strategy.
 * @param S the per-tick mutable state type.
 * @param R the sealed result type produced by this strategy.
 */
interface Strategy<C, S : StrategyContext, R> {

    /**
     * Reset internal state when the strategy is first enabled or re-enabled.
     * Called once by the adapter module's `onEnable`.
     */
    fun reset() {}

    /**
     * Execute the strategy for one tick / one invocation.
     *
     * @param config  immutable snapshot of user configuration
     * @param state   mutable per-session state (reaction timers, overshoot FSM, etc.)
     * @param input   domain-specific input (velocity context, combat context, etc.)
     * @return a typed result that the adapter maps to a [io.switchlite.core.model.PlatformCommand]
     */
    fun execute(config: C, state: S, input: Any): R

    /**
     * Human-readable name for logging and debugging.
     */
    val name: String get() = this::class.simpleName ?: "Strategy"
}

/**
 * Base interface for per-strategy mutable state.
 *
 * Implementations carry tick-to-tick state such as reaction delay counters,
     * overshoot state machines, pending click flags, etc.
 * All state is isolated per strategy instance — no shared mutable state.
 */
interface StrategyContext {
    /**
     * Reset all fields to their initial values.
     * Called when the owning module is disabled/re-enabled.
     */
    fun reset()
}
