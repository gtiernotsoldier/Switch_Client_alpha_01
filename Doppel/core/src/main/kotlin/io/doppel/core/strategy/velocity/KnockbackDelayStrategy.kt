package io.doppel.core.strategy.velocity

import io.doppel.core.condition.ConditionChecker
import io.doppel.contract.model.VelocityContext
import io.doppel.contract.option.TriggerOptions
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.contract.util.Vec3
import kotlin.random.Random

/**
 * KnockbackDelay strategy — hold back the knockback we receive and re-apply it later.
 *
 * This changes WHEN the knockback lands, never HOW MUCH: the motion value is carried through
 * untouched and released at a later tick. If Velocity also runs (it is earlier in the chain) the
 * motion being delayed is whatever Velocity left behind.
 *
 * The model is Slinky's, deliberately: one random delay drawn from 1..[Config.maxDelayTicks], gated
 * by [Config.chance] and by the shared condition engine. Three knobs that used to live here are
 * gone for the same reason — each of them made this module do something it is not supposed to:
 *
 *  - **distance-proportional extra delay** made the delay an observable function of range, so the
 *    delay length was a learnable signature instead of a constant. Slinky uses distance as a gate
 *    ("lower this to prevent the module activating when you are still too far away"), which is a
 *    yes/no and leaks nothing;
 *  - **± jitter around a fixed base** is what the uniform draw over 1..max already does, and more
 *    directly — a random range *is* the unpredictability, there is no separate knob for it;
 *  - **a release-time reduction** re-cut the motion, which pulled the SIZE channel back into a
 *    module whose only job is timing. That would stack a physical-layer anomaly on top of a timing
 *    anomaly rather than replacing one with the other.
 */
class KnockbackDelayStrategy {

    data class Config(
        /** Probability that a given knockback is delayed (0-100). */
        val chance: Int = 100,
        /**
         * Upper bound of the delay, in ticks. The actual delay is uniform over 1..this.
         */
        val maxDelayTicks: Int = 4,
        /** Require the held item to be a weapon (sword/axe). */
        val requireWeapon: Boolean = false,
        val triggerOptions: TriggerOptions = TriggerOptions()
    )

    class State {
        data class Entry(val motion: Vec3, val releaseTick: Int)
        val queue = mutableListOf<Entry>()
        var tickCounter: Int = 0
        fun reset() {
            queue.clear()
            tickCounter = 0
        }
    }

    /**
     * Called when a velocity packet arrives. Returns true when the packet was swallowed (delayed)
     * and should therefore be cancelled; false when it should pass through untouched.
     */
    fun enqueue(config: Config, state: State, ctx: VelocityContext): Boolean {
        val player = ctx.player
        val target = ctx.target

        if (config.requireWeapon && player.weaponType == WeaponType.OTHER) return false
        if (!ConditionChecker.check(config.triggerOptions, player, target)) return false
        if (config.chance < 100 && Random.nextInt(100) >= config.chance) return false

        val delay = Random.nextInt(1, config.maxDelayTicks.coerceAtLeast(1) + 1)

        state.queue.add(
            State.Entry(
                motion = ctx.originalMotion,
                releaseTick = state.tickCounter + delay
            )
        )
        return true
    }

    /**
     * Called every tick. Returns the motions whose delay expired — unchanged, because this
     * strategy owns timing and nothing else.
     *
     * [Config] is taken so the signature stays stable if a queue-policy knob is ever needed; the
     * delay itself is already decided at [enqueue] time.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pump(config: Config, state: State, currentTick: Int): List<Vec3> {
        state.tickCounter = currentTick
        val released = mutableListOf<Vec3>()
        val it = state.queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (currentTick < e.releaseTick) continue
            released.add(e.motion)
            it.remove()
        }
        return released
    }
}
