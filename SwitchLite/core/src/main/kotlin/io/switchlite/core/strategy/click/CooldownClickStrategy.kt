package io.switchlite.core.strategy.click

import io.switchlite.core.algorithm.NoiseProvider
import io.switchlite.core.condition.ConditionChecker

/**
 * 1.9+ cooldown-based click strategy with critical hit support.
 *
 * In 1.9+ Minecraft the attack cooldown governs when the player can deal
 * full damage. This strategy waits for the cooldown to reach
 * [CooldownClickConfig.cooldownThreshold] before emitting a click.
 *
 * ## CritMode.OFF
 *
 * Per-tick pipeline:
 * 1. Block-hit prevention → Skip.
 * 2. Target + condition check → Skip.
 * 3. Cooldown check: `attackCooldown >= threshold` → Skip if not ready.
 * 4. LEGIT mode: random delay 1-3 ticks after cooldown ready.
 * 5. Emit [ClickResult.Click].
 *
 * ## CritMode.ON (full crit state machine)
 *
 * Every hit is a critical hit. Multi-phase FSM:
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
 *
 * ## CritMode.SMART (opportunistic crit)
 *
 * Crit if conditions are already met; otherwise hit normally without waiting.
 *
 * - If falling + not sprinting → Click (natural crit, no FSM).
 * - If sprinting + critStopSprint → StopSprint, then Click next tick.
 *   (Whether it crits depends on whether the player is falling by then.)
 * - Otherwise → Click immediately (normal hit).
 *
 * Uses a simplified two-phase sequence: `SMART_STOP_SPRINT → SMART_RESTORE`.
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

        // 2. If in a crit sequence (ON or SMART), drive the FSM first.
        //    This takes priority over target loss — if we already stopped
        //    sprinting we must restore it.
        if (config.critMode != CritMode.OFF && state.critPhase != CritPhase.IDLE) {
            return driveCritPhase(config, state, input)
        }

        // 3. Target + condition check
        if (target == null || !ConditionChecker.check(config.triggerOptions, player, target)) {
            return ClickResult.Skip
        }

        // 4. Cooldown check
        if (input.attackCooldown < config.cooldownThreshold) {
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

        // 7. Cooldown ready — decide crit behaviour
        state.cooldownReady = false

        when (config.critMode) {
            CritMode.OFF -> {
                // No crit logic, just click
                return ClickResult.Click
            }
            CritMode.ON -> {
                // Full crit FSM: every hit must be a crit
                return startCritSequence(config, state, player, input)
            }
            CritMode.SMART -> {
                // Opportunistic: crit if possible, otherwise click now
                return smartCritDecision(config, state, player, input)
            }
        }
    }

    // ---- ON mode: full crit FSM ----

    /**
     * Enter the crit state machine. Called when cooldown is ready
     * and [CritMode.ON] is active.
     */
    private fun startCritSequence(
        config: CooldownClickConfig,
        state: CritState,
        player: io.switchlite.core.model.PlayerState,
        input: ClickInput
    ): ClickResult {
        if (config.critStopSprint && player.isSprinting) {
            state.wasSprintingBeforeCrit = true
            state.critPhase = CritPhase.STOP_SPRINT
            return ClickResult.StopSprint
        }
        // Not sprinting (or critStopSprint off) — wait for fall
        state.critPhase = CritPhase.WAIT_FALL
        return ClickResult.Skip
    }

    // ---- SMART mode: opportunistic crit ----

    /**
     * SMART crit decision. If the player can crit right now (falling +
     * not sprinting), click immediately. If sprinting, stop sprint and
     * click next tick. Otherwise, click as a normal hit.
     */
    private fun smartCritDecision(
        config: CooldownClickConfig,
        state: CritState,
        player: io.switchlite.core.model.PlayerState,
        input: ClickInput
    ): ClickResult {
        // Already in crit position — click for a natural crit
        if (input.isFalling && !player.isSprinting) {
            return ClickResult.Click
        }
        // Sprinting — stop sprint, click next tick
        if (config.critStopSprint && player.isSprinting) {
            state.wasSprintingBeforeCrit = true
            state.critPhase = CritPhase.SMART_STOP_SPRINT
            return ClickResult.StopSprint
        }
        // Can't crit — hit normally, don't wait
        return ClickResult.Click
    }

    // ---- Crit state machine ----

    private fun driveCritPhase(
        config: CooldownClickConfig,
        state: CritState,
        input: ClickInput
    ): ClickResult {
        return when (state.critPhase) {
            // ---- ON mode phases ----

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
                resetCritState(state)
                ClickResult.Skip
            }
            CritPhase.RESTORE -> {
                // Restore was emitted last tick, done
                resetCritState(state)
                ClickResult.Skip
            }

            // ---- SMART mode phases ----

            CritPhase.SMART_STOP_SPRINT -> {
                // Sprint was stopped last tick, click now
                state.critPhase = CritPhase.SMART_RESTORE
                ClickResult.Click
            }
            CritPhase.SMART_RESTORE -> {
                // Click was emitted last tick, restore sprint if needed
                if (config.critStopSprint && state.wasSprintingBeforeCrit) {
                    resetCritState(state)
                    return ClickResult.RestoreSprint(wasSprinting = true)
                }
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

        override fun reset() {
            super.reset()
            resetCritState(this)
        }
    }
}
