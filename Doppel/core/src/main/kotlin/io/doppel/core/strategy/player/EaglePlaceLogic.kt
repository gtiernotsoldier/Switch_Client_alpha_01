package io.doppel.core.strategy.player

/**
 * Eagle AutoPlace's per-tick decision — the **oracle**.
 *
 * The live path is `strategy::eagle_place` in `core-rs`; `EaglePlaceParityTest`
 * replays the golden fixture through this object to prove the two agree.
 * Nothing in the shipped agent refers to it — `:core` is not a dependency of
 * any runtime module.
 *
 * The module keeps only judgements (`shouldAutoPlace`, the pitch tolerance
 * comparison) and platform I/O. Everything stateful lives here: the aim in
 * flight, the ticks spent waiting, the placement cooldown and its random draw.
 *
 * Mirrors the original `Eagle.autoPlaceTick` + `placeDecision`:
 *  - gates closed                → cancel any pending aim, forget the cooldown;
 *  - cooling down, not aiming    → spend one tick, no draw;
 *  - view inside tolerance       → CLICK, roll a fresh cooldown;
 *  - aim timed out / BridgeAssist owns the view → ABORT, retry in one tick;
 *  - otherwise                   → AIM, count one waiting tick.
 */
object EaglePlaceLogic {

    data class State(
        val aiming: Boolean = false,
        val aimTicks: Int = 0,
        val cooldownTicks: Int = 0
    )

    data class Input(
        val gatesOpen: Boolean,
        val pitchInTolerance: Boolean,
        val bridgeAssistEnabled: Boolean,
        val maxWaitTicks: Int,
        val cooldownMin: Int,
        val cooldownMax: Int
    )

    enum class Step(val code: Int) {
        NONE(0), AIM(1), CLICK(2), ABORT(3)
    }

    data class Decision(val step: Step, val state: State)

    /**
     * @param rollCooldown invoked **only** on a CLICK, so the caller's
     *        randomness never advances on a cooling-down or aiming tick.
     */
    fun tick(state: State, input: Input, rollCooldown: () -> Int): Decision {
        if (!input.gatesOpen) {
            return Decision(Step.NONE, State())
        }
        if (!state.aiming && state.cooldownTicks > 0) {
            return Decision(Step.NONE, state.copy(cooldownTicks = state.cooldownTicks - 1))
        }

        val step = when {
            input.pitchInTolerance -> Step.CLICK
            state.aimTicks >= input.maxWaitTicks -> Step.ABORT
            input.bridgeAssistEnabled -> Step.ABORT
            else -> Step.AIM
        }

        val next = when (step) {
            Step.AIM -> State(aiming = true, aimTicks = state.aimTicks + 1, cooldownTicks = 0)
            Step.CLICK -> State(
                aiming = false,
                aimTicks = 0,
                // Random.nextInt(min, max + 1) is the inclusive [min, max].
                cooldownTicks = rollCooldown().coerceAtLeast(0)
            )
            Step.ABORT -> State(aiming = false, aimTicks = 0, cooldownTicks = 1)
            Step.NONE -> State()
        }
        return Decision(step, next)
    }
}
