package io.doppel.adapter.common.module.combat

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.strategy.click.WeaponType

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
 */
object LagRange : Module("LagRange", Category.COMBAT) {

    private val range by float("Range", 4.0f, 2.0f..6.0f, "blocks")
    private val maxDelay by int("MaxDelay", 200, 50..500, "ms")
    private val sprintReset by boolean("SprintReset", true)
    private val blockSword by boolean("BlockSword", true)
    private val holdingWeapon by boolean("HoldingWeapon", true)

    private var lastDist: Float = -1f
    private var lastSelfHurt: Int = 0
    private var lastSprint: Boolean = false
    private var lastBlocking: Boolean = false
    private var holdStartNano: Long = 0L

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        val now = System.nanoTime()
        val dist = target?.distance

        if (EventBridge.isGuiOpen || dist == null || dist > range) {
            flush()
            lastDist = -1f
            return
        }

        // Damage taken: never held through it.
        if (player.hurtTime > lastSelfHurt) {
            lastSelfHurt = player.hurtTime
            flush()
            lastDist = dist
            return
        }
        lastSelfHurt = player.hurtTime

        if (sprintReset) {
            if (player.isSprinting && !lastSprint) {
                lastSprint = true
                flush()
                lastDist = dist
                return
            }
            lastSprint = player.isSprinting
        }

        if (blockSword) {
            if (player.isBlocking && !lastBlocking) {
                lastBlocking = true
                flush()
                lastDist = dist
                return
            }
            lastBlocking = player.isBlocking
        }

        if (holdingWeapon && player.weaponType != WeaponType.SWORD) {
            flush()
            lastDist = dist
            return
        }

        // Not closing in any more: the delay buys nothing, so let everything go.
        if (lastDist >= 0f && dist >= lastDist) {
            flush()
            lastDist = dist
            return
        }
        lastDist = dist

        if (holdStartNano == 0L) {
            holdStartNano = now
            markWorked() // a hold starting is a transition, like every other module's work
        } else {
            EventBridge.packetLagActive = true
        }
        EventBridge.packetLagActive = true

        if (now - holdStartNano >= maxDelay * 1_000_000L) flush()
    }

    private fun flush() {
        holdStartNano = 0L
        EventBridge.packetLagActive = false
    }

    override fun onEnable() {
        lastDist = -1f
        lastSelfHurt = 0
        lastSprint = false
        lastBlocking = false
        holdStartNano = 0L
        EventBridge.packetLagActive = false
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        flush()
    }
}
