package io.switchlite.core.strategy.velocity

import io.switchlite.core.condition.ConditionChecker
import io.switchlite.core.model.VelocityContext
import io.switchlite.core.option.TriggerOptions
import io.switchlite.core.strategy.click.WeaponType
import io.switchlite.core.util.Vec3
import kotlin.random.Random

/**
 * Independent KnockbackDelay strategy.
 *
 * Delays incoming knockback (already reduced by Velocity if that module runs earlier in the
 * velocity chain) with a DISTANCE-AWARE dynamic delay + random jitter, then releases it with an
 * optional final reduction.
 *
 * Independent algorithm (vs Slinky's fixed random delay):
 *  - dynamic delay: farther targets → longer delay (unpredictable at range)
 *  - jitter: ±ticks so the delay never forms a fixed, detectable rhythm
 *  - release reduce: optional extra cut applied at release time
 */
class KnockbackDelayStrategy {

    data class Config(
        val chance: Int = 100,
        val minDelayTicks: Int = 1,
        val maxDelayTicks: Int = 4,
        /** Extra delay ticks per block of distance to the target. */
        val distanceFactor: Float = 0.5f,
        /** Random ±jitter applied to the delay, in ticks. */
        val jitterTicks: Int = 1,
        /** Optional motion scale applied at release time (1.0 = unchanged). */
        val releaseReduce: Float = 1.0f,
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

        // Dynamic delay: base range + distance-proportional extra + random jitter.
        val min = config.minDelayTicks.coerceAtLeast(1)
        val max = config.maxDelayTicks.coerceAtLeast(min)
        val base = Random.nextInt(min, max + 1)
        val distExtra = ((target?.distance ?: 0f) * config.distanceFactor).toInt()
        val jitter = Random.nextInt(-config.jitterTicks, config.jitterTicks + 1)
        val total = (base + distExtra + jitter).coerceAtLeast(1)

        state.queue.add(
            State.Entry(
                motion = ctx.originalMotion,
                releaseTick = state.tickCounter + total
            )
        )
        return true
    }

    /**
     * Called every tick. Returns the motions whose delay expired, with [Config.releaseReduce]
     * already applied.
     */
    fun pump(config: Config, state: State, currentTick: Int): List<Vec3> {
        state.tickCounter = currentTick
        val factor = config.releaseReduce.coerceIn(0f, 1f).toDouble()
        val released = mutableListOf<Vec3>()
        val it = state.queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (currentTick < e.releaseTick) continue
            released.add(Vec3(e.motion.x * factor, e.motion.y * factor, e.motion.z * factor))
            it.remove()
        }
        return released
    }
}
