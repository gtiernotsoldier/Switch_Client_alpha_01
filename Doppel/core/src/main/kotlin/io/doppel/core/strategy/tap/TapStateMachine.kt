package io.doppel.core.strategy.tap

/**
 * Shared state machine for tap-based movement modules (WTap, STap).
 *
 * Manages the three-phase lifecycle and nanoTime-based timers:
 *   IDLE → POST_DELAY → TAPPING → IDLE
 *
 * Pure Core layer: zero platform dependencies, no key simulation.
 * The module feeds events and receives phase-change signals.
 *
 * Usage per tick:
 * ```
 * val event = machine.tick(nowNs)
 * when (event) {
 *   END_TAP -> pressKeyToRestore()
 *   SHOULD_START_TAP -> { val ms = randomDuration(); machine.beginTap(nowNs, ms); pressTapKey() }
 *   NONE -> {}
 * }
 * // When trigger fires:
 * if (postDelayMs > 0) machine.beginPostDelay(nowNs, postDelayMs)
 * else { val ms = randomDuration(); machine.beginTap(nowNs, ms); pressTapKey() }
 * ```
 */
class TapStateMachine {

    enum class Phase { IDLE, POST_DELAY, TAPPING }

    enum class Event {
        /** No state change this tick. */
        NONE,
        /** TAPPING timer expired — module should restore the key. */
        END_TAP,
        /** POST_DELAY timer expired — module should start the tap action. */
        SHOULD_START_TAP
    }

    var phase: Phase = Phase.IDLE
        private set

    /** nanoTime when the current tap ends (TAPPING phase). */
    var tapEndNano: Long = 0L
        private set

    private var postDelayEndNano: Long = 0L

    /**
     * Advance the state machine for one tick.
     * @return the event the module should handle.
     */
    fun tick(nowNs: Long): Event {
        return when (phase) {
            Phase.TAPPING -> {
                if (nowNs >= tapEndNano) {
                    phase = Phase.IDLE
                    Event.END_TAP
                } else Event.NONE
            }
            Phase.POST_DELAY -> {
                if (nowNs >= postDelayEndNano) {
                    Event.SHOULD_START_TAP
                } else Event.NONE
            }
            Phase.IDLE -> Event.NONE
        }
    }

    /**
     * Enter POST_DELAY phase. Caller must start the tap when [Event.SHOULD_START_TAP] fires.
     */
    fun beginPostDelay(nowNs: Long, delayMs: Int) {
        postDelayEndNano = nowNs + delayMs * 1_000_000L
        phase = Phase.POST_DELAY
    }

    /**
     * Enter TAPPING phase with the given duration.
     * Caller is responsible for pressing the tap key immediately.
     */
    fun beginTap(nowNs: Long, durationMs: Int) {
        tapEndNano = nowNs + durationMs * 1_000_000L
        phase = Phase.TAPPING
    }

    fun reset() {
        phase = Phase.IDLE
    }
}
