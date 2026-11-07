package io.doppel.contract.strategy.click

/**
 * Phases of the critical hit state machine.
 *
 * Lives next to [ClickStrategy] rather than inside the implementation so that
 * [io.doppel.ipc.RemoteCooldownClickStrategy] can name it without depending on
 * the algorithm module — the state is part of the call contract, not the math.
 */
enum class CritPhase {
    /** Normal operation, no crit sequence active. */
    IDLE,

    // -- ON mode phases --
    /** [ClickResult.StopSprint] emitted, waiting one tick (ON mode). */
    STOP_SPRINT,
    /** Waiting for the player to be falling (ON mode). */
    WAIT_FALL,
    /** Waiting for attack cooldown to reach threshold (ON mode). */
    WAIT_COOLDOWN,
    /** [ClickResult.Click] emitted, about to restore (ON mode). */
    ATTACK,
    /** [ClickResult.RestoreSprint] emitted, about to go IDLE (ON mode). */
    RESTORE,

    // -- SMART mode phases --
    /** [ClickResult.StopSprint] emitted, will click next tick (SMART mode). */
    SMART_STOP_SPRINT,
    /** [ClickResult.Click] emitted, will restore sprint next tick (SMART mode). */
    SMART_RESTORE
}

/**
 * Extended state for 1.9+ cooldown clicking with crit support.
 */
class CritState : ClickStrategy.State() {
    /**
     * Current phase of the crit state machine.
     * [CritPhase.IDLE] when no crit sequence is active.
     */
    var critPhase: CritPhase = CritPhase.IDLE

    /**
     * True when the cooldown bar has reached threshold.
     */
    var cooldownReady: Boolean = false

    /**
     * Remaining ticks of LEGIT-mode random delay after cooldown ready.
     */
    var legitDelayTicks: Int = 0

    /**
     * Whether the player was sprinting before we stopped them for crit.
     */
    var wasSprintingBeforeCrit: Boolean = false

    /** WAIT_FALL 阶段已等待的 tick 数，用于超时降级。 */
    var waitFallTicks: Int = 0

    override fun reset() {
        super.reset()
        critPhase = CritPhase.IDLE
        wasSprintingBeforeCrit = false
        cooldownReady = false
        legitDelayTicks = 0
        waitFallTicks = 0
    }
}
