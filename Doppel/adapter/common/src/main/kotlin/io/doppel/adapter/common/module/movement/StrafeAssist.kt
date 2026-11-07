package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.ipc.Strategies

/**
 * StrafeAssist — damps the movement keys that make the player easier to track.
 *
 * It does not drive the player. It reads the **physical** WASD, asks the core whether
 * releasing exactly one of them would improve the geometry, and — when the answer is clearly yes —
 * suppresses that one key for the tick. The player's input is the baseline; this only removes what
 * the geometry says is counter-productive.
 *
 * Physical keys are read on purpose: reading the effective (arbitrated) state would feed the
 * previous tick's own suppression back in and the module would oscillate.
 *
 * Suppression is a SUPPRESS claim, not OWNED: another module that wants the key pressed (a brace,
 * a tap) still wins. A correction must never silently disable another module.
 */
object StrafeAssist : Module("StrafeAssist", Category.MOVEMENT) {

    /** Damping forward is the main effect: inside reach, W only enlarges the player's angular size. */
    private val dampForward by boolean("DampForward", true)

    /** Damping back: S trades distance for speed and is rarely the geometric optimum. */
    private val dampBack by boolean("DampBack", true)

    /**
     * Damping A/D. Off by default — the strafe keys produce the tangential share the module is
     * trying to preserve, and neither one is "wrong" without knowing which side the player circles.
     */
    private val dampStrafe by boolean("DampStrafe", false)

    /** Assumed attack reach, blocks. Used as the "close enough" line in the geometry. */
    private val reach by float("Reach", 3.0f, 2.0f..4.5f, "blocks")

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, t ->
        if (enabled) onTick(p, t)
    }

    private fun onTick(player: PlayerState, target: TargetState?) {
        // A correction lasts exactly one tick: drop the previous one before deciding again.
        releaseAll()

        if (target == null || target.health <= 0f) return
        if (player.health <= 0f) return
        if (target.distance > reach + 1.5f) return          // far away: nothing worth correcting

        val forward = EventBridge.isKeyForwardDown
        val back = EventBridge.isKeyBackDown
        val left = EventBridge.isKeyLeftDown
        val right = EventBridge.isKeyRightDown
        if (!forward && !back && !left && !right) return

        val bearing = Math.atan2(
            target.position.z - player.position.z,
            target.position.x - player.position.x
        )

        // null = no core session: leave every key alone (same as an all-false verdict).
        val suppress = Strategies.strafe(
            player, target, forward, back, left, right,
            target.distance.toDouble(), bearing,
            player.rotation.yaw.toDouble(), reach.toDouble()
        ) ?: return

        if (suppress[0] && dampForward) EventBridge.suppressForward(name)
        if (suppress[1] && dampBack) EventBridge.suppressBack(name)
        if (suppress[2] && dampStrafe) EventBridge.suppressLeft(name)
        if (suppress[3] && dampStrafe) EventBridge.suppressRight(name)

        // No markWorked(): suppressing a key produces no visible action, and a per-tick flash
        // would leave the HUD row permanently red — which is worse than no indicator at all.
    }

    private fun releaseAll() {
        EventBridge.releaseForward(name)
        EventBridge.releaseBack(name)
        EventBridge.releaseLeft(name)
        EventBridge.releaseRight(name)
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        releaseAll()
    }
}
