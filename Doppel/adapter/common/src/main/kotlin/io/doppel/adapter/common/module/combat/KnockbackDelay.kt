package io.doppel.adapter.common.module.combat

import io.doppel.core.model.PlatformCommand
import io.doppel.core.model.VelocityContext
import io.doppel.core.strategy.velocity.KnockbackDelayStrategy
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.adapter.common.option.triggerOptions

/**
 * KnockbackDelay Module — independent knockback DELAY (timing), split out of Velocity's Delay mode.
 *
 * Delays the knockback you RECEIVE instead of applying it immediately. Runs AFTER Velocity in the
 * velocity chain, so it delays the already-reduced motion (Velocity cuts size → KnockbackDelay
 * delays timing). Release uses a distance-aware dynamic delay + jitter, with an optional final
 * reduction — a richer algorithm than a fixed random delay.
 */
object KnockbackDelay : Module("KnockbackDelay", Category.COMBAT) {

    // ========== Independent algorithm knobs ==========
    private val chance by int("Chance", 100, 0..100, "%")
    private val minDelayTicks by int("MinDelayTicks", 1, 1..10, "ticks")
    private val maxDelayTicks by int("MaxDelayTicks", 4, 1..20, "ticks")
    private val distanceFactor by float("DistanceFactor", 0.5f, 0.0f..2.0f, "t/block")
    private val jitterTicks by int("JitterTicks", 1, 0..5, "ticks")
    private val releaseReduce by float("ReleaseReduce", 1.0f, 0.0f..1.0f)

    // ========== Conditions ==========
    private val maxDistance by float("MaxDistance", 6.0f, 0.0f..10.0f, "blocks")
    private val onlyGround by boolean("OnlyGround", true)
    private val lookingAtPlayer by boolean("LookingAtPlayer", false)
    private val mousePressed by boolean("MousePressed", false)
    private val requireWeapon by boolean("RequireWeapon", false)

    private val triggerOptions by triggerOptions("Trigger") {
        maxDistance = this@KnockbackDelay.maxDistance
        onlyGround = this@KnockbackDelay.onlyGround
        onlyCurrentView = this@KnockbackDelay.lookingAtPlayer
        onlyOnClick = this@KnockbackDelay.mousePressed
    }

    // ========== Core strategy (algorithm lives here) ==========
    private val strategy = KnockbackDelayStrategy()
    private val state = KnockbackDelayStrategy.State()

    private fun buildConfig() = KnockbackDelayStrategy.Config(
        chance = chance,
        minDelayTicks = minDelayTicks,
        maxDelayTicks = maxDelayTicks,
        distanceFactor = distanceFactor,
        jitterTicks = jitterTicks,
        releaseReduce = releaseReduce,
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
