package io.switchlite.core.strategy.click

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker

/**
 * 1.9+ cooldown-based click strategy with optional critical hit support.
 *
 * In 1.9+ Minecraft the attack cooldown governs when the player can deal
 * full damage. This strategy waits for the cooldown to reach
 * [CooldownClickConfig.cooldownThreshold] before emitting a click.
 *
 * ## Cooldown-only mode (critEnabled = false)
 *
 * Per-tick pipeline:
 * 1. Block-hit prevention → Skip.
 * 2. Target + condition check → Skip.
 * 3. Cooldown check: `attackCooldown >= threshold` → Skip if not ready.
 * 4. LEGIT mode: random delay 1-3 ticks after cooldown ready.
 * 5. Emit [ClickResult.Click].
 *
 * ## Crit mode (critEnabled = true)
 *
 * Crits in 1.9+ require the player to be **falling** and **not sprinting**.
 * The strategy runs a multi-phase state machine:
 *
 * ```
 * IDLE → STOP_SPRINT → WAIT_FALL → WAIT_COOLDOWN → ATTACK → RESTORE → IDLE
 * ```
 *
 * - **IDLE**: waiting for target + conditions.
 * - **STOP_SPRINT**: emit [ClickResult.StopSprint], move to WAIT_FALL.
 * - **WAIT_FALL**: wait until [ClickInput.isFalling] is true.
 * - **WAIT_COOLDOWN**: wait until cooldown >= threshold (+ LEGIT delay).
 * - **ATTACK**: emit [ClickResult.Click], move to RESTORE.
 * - **RESTORE**: emit [ClickResult.RestoreSprint], return to IDLE.
 *
 * If [CooldownClickConfig.critStopSprint] is false, STOP_SPRINT and RESTORE
 * phases are skipped (the player manages sprinting manually).
 */
class CooldownClickStrategy : ClickStrategy<CooldownClickConfig, CooldownClickStrategy.CritState> {

    override fun execute(
        config: CooldownClickConfig,
        state: CritState,
        input: Any
    ): ClickResult {
        require(input is ClickInput) { "CooldownClickStrategy expects ClickInput" }
        return processTick(config, state, input)
    }

    // ---- Public API with correct types ----

    /**
     * Execute one tick of 1.9+ cooldown-based clicking.
     */
    fun processTick(
        config: CooldownClickConfig,
        state: CritState,
        input: ClickInput
    ): ClickResult {
        val player = input.player
        val target = input.target

        // 1. Block-hit prevention
        if (config.disableOnBlock && player.isMining) {
            return ClickResult.Skip
        }

        // 2. If in crit sequence, drive the state machine first
        //    (crit sequence takes priority over target loss — if we
        //     already stopped sprinting we must restore it)
        if (config.critEnabled && state.critPhase != CritPhase.IDLE) {
            return driveCritPhase(config, state, input)
        }

        // 3. Target + condition check
        if (target == null || !ConditionChecker.check(config.triggerOptions, player, target)) {
            return ClickResult.Skip
        }

        // 4. Cooldown check
        if (input.attackCooldown < config.cooldownThreshold) {
            // Track when cooldown becomes ready for LEGIT delay
            if (!state.cooldownReady) {
                state.cooldownReady = true
            }
            return ClickResult.Skip
        }

        // 5. Cooldown just became ready (or was already ready)
        if (!state.cooldownReady) {
            state.cooldownReady = true
        }

        // 6. LEGIT mode: random delay after cooldown ready
        if (config.cooldownMode == CooldownClickMode.LEGIT) {
            if (state.legitDelayTicks == 0) {
                state.legitDelayTicks = sampleLegitDelay()
            }
            state.legitDelayTicks--
            if (state.legitDelayTicks > 0) {
                return ClickResult.Skip
            }
        }

        // 7. Cooldown ready — decide crit vs normal
        state.cooldownReady = false

        if (!config.critEnabled) {
            // Simple cooldown click, no crit
            return ClickResult.Click
        }

        // 8. Start crit sequence
        if (config.critStopSprint && player.isSprinting) {
            state.wasSprintingBeforeCrit = true
            state.critPhase = CritPhase.STOP_SPRINT
            return ClickResult.StopSprint
        }

        // Not sprinting (or critStopSprint off) — skip to wait fall
        state.critPhase = CritPhase.WAIT_FALL
        return ClickResult.Skip
    }

    // ---- Crit state machine ----

    private fun driveCritPhase(
        config: CooldownClickConfig,
        state: CritState,
        input: ClickInput
    ): ClickResult {
        return when (state.critPhase) {
            CritPhase.STOP_SPRINT -> {
                // Sprint stop was emitted last tick, now wait for fall
                state.critPhase = CritPhase.WAIT_FALL
                ClickResult.Skip
            }
            CritPhase.WAIT_FALL -> {
                if (input.isFalling) {
                    state.critPhase = CritPhase.WAIT_COOLDOWN
                    state.cooldownReady = false
                }
                ClickResult.Skip
            }
            CritPhase.WAIT_COOLDOWN -> {
                if (input.attackCooldown < config.cooldownThreshold) {
                    if (!state.cooldownReady) state.cooldownReady = true
                    return ClickResult.Skip
                }
                if (!state.cooldownReady) state.cooldownReady = true

                // LEGIT delay in crit sequence too
                if (config.cooldownMode == CooldownClickMode.LEGIT) {
                    if (state.legitDelayTicks == 0) {
                        state.legitDelayTicks = sampleLegitDelay()
                    }
                    state.legitDelayTicks--
                    if (state.legitDelayTicks > 0) return ClickResult.Skip
                }

                state.cooldownReady = false
                state.critPhase = CritPhase.ATTACK
                ClickResult.Click
            }
            CritPhase.ATTACK -> {
                // Attack was emitted last tick, now restore sprint if needed
                state.critPhase = CritPhase.RESTORE
                if (config.critStopSprint && state.wasSprintingBeforeCrit) {
                    return ClickResult.RestoreSprint(wasSprinting = true)
                }
                // No restore needed, go straight to idle
                resetCritState(state)
                ClickResult.Skip
            }
            CritPhase.RESTORE -> {
                // Restore was emitted last tick, done
                resetCritState(state)
                ClickResult.Skip
            }
            CritPhase.IDLE -> {
                // Should not reach here, but handle gracefully
                ClickResult.Skip
            }
        }
    }

    // ---- Helpers ----

    private fun sampleLegitDelay(): Int {
        return NoiseProvider.nextUniform(1f, 3f).toInt().coerceIn(1, 3)
    }

    private fun resetCritState(state: CritState) {
        state.critPhase = CritPhase.IDLE
        state.wasSprintingBeforeCrit = false
        state.cooldownReady = false
        state.legitDelayTicks = 0
    }

    // ---- State ----

    /**
     * Phases of the critical hit state machine.
     */
    enum class CritPhase {
        /** Normal operation, no crit sequence active. */
        IDLE,
        /** [ClickResult.StopSprint] was just emitted, waiting one tick. */
        STOP_SPRINT,
        /** Waiting for the player to be falling (negative motionY, airborne). */
        WAIT_FALL,
        /** Waiting for attack cooldown to reach threshold. */
        WAIT_COOLDOWN,
        /** [ClickResult.Click] was just emitted. */
        ATTACK,
        /** [ClickResult.RestoreSprint] was just emitted. */
        RESTORE
    }

    /**
     * Extended state for 1.9+ cooldown clicking with crit support.
     *
     * Extends the base [ClickStrategy.State] with cooldown tracking,
     * LEGIT delay counter, and the crit phase machine.
     */
    class CritState : ClickStrategy.State() {
        /**
         * Current phase of the crit state machine.
         * [CritPhase.IDLE] when no crit sequence is active.
         */
        var critPhase: CritPhase = CritPhase.IDLE

        /**
         * True if the cooldown bar has reached [CooldownClickConfig.cooldownThreshold]
         * and we are in the tick it first became ready (for LEGIT delay start).
         */
        var cooldownReady: Boolean = false

        /**
         * Remaining ticks of LEGIT-mode random delay after cooldown ready.
         * 0 means no delay pending.
         */
        var legitDelayTicks: Int = 0

        /**
         * Whether the player was sprinting before we stopped them for crit.
         * Used to decide whether to restore sprinting after the crit hit.
         */
        var wasSprintingBeforeCrit: Boolean = false

        override fun reset() {
            super.reset()
            resetCritState(this)
        }
    }
}
