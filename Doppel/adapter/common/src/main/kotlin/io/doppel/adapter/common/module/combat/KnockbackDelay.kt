package io.doppel.adapter.common.module.combat

import io.doppel.contract.model.PlatformCommand
import io.doppel.contract.model.VelocityContext
import io.doppel.core.strategy.velocity.KnockbackDelayStrategy
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions

/**
 * KnockbackDelay Module — delay the knockback we RECEIVE.
 *
 * Holds the packet back and re-applies the same motion N ticks later, so this module changes WHEN
 * the knockback lands, never HOW MUCH. It runs after Velocity in the velocity chain, so when both
 * are on it is the already-reduced motion that gets delayed.
 *
 * The option set mirrors Slinky's Knockback Delay: a distance GATE, a chance, one maximum delay,
 * and the four conditions (in air / looking at player / mouse pressed / holding weapon). Three knobs
 * this module used to carry are gone with it — [KnockbackDelayStrategy] documents why each was
 * removed.
 *
 * What this is NOT: the delay is implemented by cancelling the packet and replaying the motion from
 * the client, so it is not "the network was slow". A server that validates knockback position sees
 * the first tick's displacement missing entirely — the same signature as a full reduction, and the
 * longer the delay the larger the peak error. Whether that matters is server-specific; the
 * tolerance is deliberately not a constant in this codebase.
 */
object KnockbackDelay : Module("KnockbackDelay", Category.COMBAT) {

    // ========== Timing ==========
    /** Probability that a given knockback is delayed. */
    private val chance by int("Chance", 100, 0..100, "%")

    /**
     * Maximum delay, in ticks. The actual delay is drawn uniformly from 1..this, and that range is
     * the whole mechanism — there is deliberately no separate jitter knob.
     */
    private val maxDelayTicks by int("MaxDelayTicks", 4, 1..20, "ticks")

    // ========== Conditions ==========
    /**
     * Distance to target — a GATE. Lower it to stop the module firing when you are still out of
     * reach (a rod or an arrow landing from 20 blocks away should not delay anything).
     */
    private val maxDistance by float("MaxDistance", 6.0f, 0.0f..10.0f, "blocks")

    /**
     * Delay only while airborne. On by default, and that default is the point of the module rather
     * than a taste: air knockback travels furthest (air friction is 0.91 against 0.546 on the
     * ground), and it is the case a server has the least hope of predicting. Turning it off to also
     * delay grounded knockback usually only makes sense alongside a high MaxDelayTicks, where what
     * you want is the timing shift itself.
     *
     * (This is the inverse of the old `OnlyGround = true`, which made the module fire on the ground
     * and stay quiet in the air — the opposite of where it pays.)
     */
    private val inAir by boolean("InAir", true)

    /** Require the crosshair to be on a player. */
    private val lookingAtPlayer by boolean("LookingAtPlayer", false)

    /** Require the attack key to be held. */
    private val mousePressed by boolean("MousePressed", false)

    /** Require the held item to be a weapon (sword/axe). */
    private val requireWeapon by boolean("RequireWeapon", false)

    private val triggerOptions by triggerOptions("Trigger") {
        maxDistance = this@KnockbackDelay.maxDistance
        onlyAir = this@KnockbackDelay.inAir
        onlyCurrentView = this@KnockbackDelay.lookingAtPlayer
        onlyOnClick = this@KnockbackDelay.mousePressed
    }

    // ========== Core strategy (algorithm lives here) ==========
    private val strategy = KnockbackDelayStrategy()
    private val state = KnockbackDelayStrategy.State()

    private fun buildConfig() = KnockbackDelayStrategy.Config(
        chance = chance,
        maxDelayTicks = maxDelayTicks,
        requireWeapon = requireWeapon,
        triggerOptions = triggerOptions
    )

    // ========== Listeners ==========
    private val velocityListenerRef: (VelocityContext) -> PlatformCommand = { ctx ->
        if (enabled) onVelocityPacket(ctx) else PlatformCommand.Pass(ctx.originalMotion)
    }
    private val tickListener: (Int) -> Unit = { tick ->
        if (enabled) onTick(tick)
    }

    fun onVelocityPacket(ctx: VelocityContext): PlatformCommand {
        val config = cachedConfig { buildConfig() }
        val swallowed = strategy.enqueue(config, state, ctx)
        return if (swallowed) {
            // The packet was actually held back: that is the module working.
            markWorked()
            PlatformCommand.CancelPacket(ctx.packetHandle)
        } else {
            PlatformCommand.Pass(ctx.originalMotion)
        }
    }

    fun onTick(currentTick: Int) {
        val config = cachedConfig { buildConfig() }
        for (motion in strategy.pump(config, state, currentTick)) {
            EventBridge.applyMotion(motion)
        }
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        state.reset()
        EventBridge.registerVelocityListener(velocityListenerRef, EventBridge.VELOCITY_PRIORITY_DELAY)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityListener(velocityListenerRef)
        EventBridge.unregisterTickListener(tickListener)
        state.reset()
    }
}
