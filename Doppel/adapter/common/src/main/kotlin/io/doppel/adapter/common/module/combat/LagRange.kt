package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.contract.strategy.click.WeaponType
import io.doppel.ipc.LagRangeStep
import io.doppel.ipc.Strategies

/**
 * LagRange — hold your own packets while closing in, so the server keeps computing positions from where
 * you were a moment ago.
 *
 * Ported from raven-bs's LagRange (its `maximumDelay`, distance gate and flush conditions). The hold is
 * released the instant anything would be harmed by the delay, which is what makes it usable rather than a
 * stall:
 *
 *  * the target leaves [range], or the distance **stops shrinking** — the hold only exists while closing
 *    in, and a target that is not getting closer is one where the delay buys nothing;
 *  * damage taken;
 *  * a sprint starting ([sprintReset]) and a block starting ([blockSword]) — both are transitions the
 *    server has to see at the moment they happen;
 *  * the weapon being dropped ([holdingWeapon]);
 *  * a screen opening;
 *  * and [maxDelay] at the latest, so a hold is bounded even if none of the above fires.
 *
 * Nothing is drawn: this client's overlay is 2D and the reference's real-position marker is a world-space
 * draw, so the module is the network behaviour without the marker rather than a marker that does not work.
 *
 * ── Where the state lives now
 *
 * This side holds only **judgements + marshalling + platform writes**: the GUI-open check, the
 * target's distance sample, the weapon being a sword, and the single `packetLagActive` write.
 * Everything stateful lives in the algorithm process (`strategy::lag_range`): the previous
 * distance, the damage / sprint / block edges and the hold's start timestamp. There is no
 * `kotlin.random` and no local time window — `System.nanoTime()` is an input we marshal. A missing
 * answer means the packets go through: holding is the state that can desync the view, so the safe
 * direction when the core is unreachable is not to hold at all.
 */
object LagRange : Module("LagRange", Category.COMBAT) {

    private val range by float("Range", 4.0f, 2.0f..6.0f, "blocks")
    private val maxDelay by int("MaxDelay", 200, 50..500, "ms")
    private val sprintReset by boolean("SprintReset", true)
    private val blockSword by boolean("BlockSword", true)
    private val holdingWeapon by boolean("HoldingWeapon", true)

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val dist = target?.distance

        val step = Strategies.lagRange(
            player, target,
            System.nanoTime(),
            EventBridge.isGuiOpen,
            dist != null,
            (dist ?: 0f).toDouble(),
            range.toDouble(),
            maxDelay,
            sprintReset,
            player.isSprinting,
            blockSword,
            player.isBlocking,
            holdingWeapon,
            player.weaponType == WeaponType.SWORD,
            player.hurtTime
        )

        when (step) {
            LagRangeStep.ARM -> {
                // A hold starting is a transition, like every other module's work.
                EventBridge.packetLagActive = true
                markWorked()
            }
            LagRangeStep.HOLD -> EventBridge.packetLagActive = true
            LagRangeStep.FLUSH -> EventBridge.packetLagActive = false
            // No answer: never hold packets on a guess — same direction as
            // the flush, and exactly Backtrack's reasoning.
            null -> EventBridge.packetLagActive = false
            LagRangeStep.NONE -> Unit
        }
    }

    override fun onEnable() {
        // A fresh enable must not inherit a distance base, an edge tracker
        // or a hold start the core still believes it owns.
        Strategies.resetPlayer()
        EventBridge.packetLagActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        EventBridge.packetLagActive = false
        Strategies.resetPlayer()
    }
}
