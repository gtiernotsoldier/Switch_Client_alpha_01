package io.doppel.core.strategy.velocity

import io.doppel.core.condition.ConditionChecker
import io.doppel.contract.model.VelocityContext
import kotlin.random.Random
import io.doppel.contract.strategy.velocity.VelocityConfig
import io.doppel.contract.strategy.velocity.VelocityResult
import io.doppel.contract.strategy.velocity.VelocityStrategy

/**
 * SPRINT mode velocity strategy — the one mode that never rewrites the packet.
 *
 * The other strategies decide what to do *to* the velocity. This one returns [VelocityResult.Pass]
 * unconditionally and instead **arms** a window during which the adapter presses movement keys
 * toward the knockback source (see [SprintBrace] for the arithmetic and the reasoning). Because the
 * result is `Pass`, vanilla applies the knockback itself and there is no prediction error to find —
 * the trade is that the achievable reduction is bounded by your own speed against the arc.
 *
 * Split of responsibility:
 *  - here: condition check, the gate roll, arming the window, and the per-tick key resolution
 *  - adapter: reading player state, touching keys, sending C0B, deciding whether to sync
 *
 * `arm()` is called once per knockback (Netty thread); `braceKeys()` is called once per tick
 * (tick thread) with the player's current yaw, because the key mapping depends on where the player
 * is facing at that moment, not at the moment of the hit.
 */
class SprintVelocityStrategy : VelocityStrategy {

    override fun execute(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        input: Any
    ): VelocityResult {
        require(input is VelocityContext) { "SprintVelocityStrategy expects VelocityContext" }
        // There is never anything to do *to* the packet in this mode.
        arm(config, state, input)
        return VelocityResult.Pass(input.originalMotion)
    }

    /**
     * Check the conditions, roll the gate and, if it passes, open the brace window.
     *
     * Public rather than `internal` because the adapter lives in a different module and needs to
     * know whether to flash the HUD row. Returns true when this hit armed the window.
     */
    fun arm(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        ctx: VelocityContext
    ): Boolean {
        if (!ConditionChecker.check(config.triggerOptions, ctx.player, ctx.target)) return false

        // Direction first, dice second. A hit with no horizontal component is not a brace decision
        // at all, so it must not consume a roll — otherwise `braceLastPassed` records "chose to
        // brace" for a hit that could never be braced, and the next hit's gate uses the wrong state.
        val source = SprintBrace.sourceFrom(ctx.originalMotion) ?: return false

        val passed = SprintBrace.gateRoll(
            Random, state.braceLastPassed, config.sprintGateBraced, config.sprintGateIdle
        )
        state.braceLastPassed = passed
        if (!passed) return false

        // Publish the whole window as one reference — arm() runs on the packet thread.
        state.braceWindow = VelocityStrategy.State.BraceWindow(
            source.x, source.z, config.sprintHoldTicks
        )
        return true
    }

    /**
     * One tick of the brace: consume a tick from the armed window and resolve the keys for the
     * player's *current* facing. Returns null when the window is closed (the adapter then releases
     * whatever it was holding).
     *
     * A new window is adopted by identity, so any tick that sees a fresh [State.braceWindow] starts
     * the countdown from that window's own length instead of inheriting a stale remainder.
     */
    fun braceKeys(
        config: VelocityConfig,
        state: VelocityStrategy.State,
        yawDegrees: Float
    ): BraceKeys? {
        val window = state.braceWindow ?: return null
        if (window !== state.braceAdopted) {
            state.braceAdopted = window
            state.braceTicksLeft = window.ticks
        }
        if (state.braceTicksLeft <= 0) return null
        state.braceTicksLeft--
        return SprintBrace.keysFor(
            yawDegrees,
            BraceSource(window.sourceX, window.sourceZ),
            config.sprintSector.toDouble()
        )
    }
}
