package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.core.strategy.movement.StopTap

/**
 * Instant Stop — brake to a halt when the player lets go of a movement key.
 *
 * Vanilla only stops accelerating; friction then bleeds the velocity off over several ticks. This
 * module instead does what a player does manually: tap the opposite key for a moment.
 *
 * ── Why a tap and not a motion write
 *
 * Zeroing `motionX/Z` is the obvious implementation and the one every movement check is built to
 * catch: the player's position stops changing faster than the friction constant allows, which is a
 * single-frame anomaly no human can produce. A key press is not: the game applies it through the
 * same acceleration path as the player's own fingers, so the resulting motion is a motion the
 * server's model already covers.
 *
 * ── When the tap ends
 *
 * Not on a timer alone. [StopTap.stillSliding] projects the current velocity onto the direction
 * that was released, and the brake stops the moment that projection reaches zero — otherwise the
 * tap keeps pushing after the player has stopped and starts moving them backwards, which is slower
 * and looks like a stuck key.
 *
 * The tap also ends immediately if the player presses the released key again: they changed their
 * mind, and holding the opposite key would fight them.
 *
 * ⚠ Do not combine with a W-tap playstyle: a deliberate one-tick release becomes a two-tick stop,
 * which delays the sprint restart the tap exists to get. The module cannot tell a tap from a stop —
 * it only sees the release. Pick one.
 */
object InstantStop : Module("InstantStop", Category.MOVEMENT) {

    /** Releasing W → tap S. */
    private val onWRelease by boolean("OnWRelease", true)

    /** Releasing S → tap W. */
    private val onSRelease by boolean("OnSRelease", true)

    /** Upper bound on the tap. Two ticks is the most that brakes without visibly reversing. */
    private val maxTicks by int("MaxTicks", 2, 1..5, "ticks")

    /** Stop braking once the residual speed along the released direction falls below this. */
    private val minSpeed by float("MinSpeed", 0.02f, 0.0f..0.2f, "b/t")

    private enum class Brake { NONE, FORWARD_RELEASED, BACK_RELEASED }

    @Volatile private var brake: Brake = Brake.NONE
    @Volatile private var brakeUntilTick: Int = -1
    @Volatile private var brakeDeadlineNanos: Long = 0L

    @Volatile private var prevForward: Boolean = false
    @Volatile private var prevBack: Boolean = false

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        val tick = EventBridge.getCurrentTick()
        val now = System.nanoTime()
        val forward = EventBridge.physicalForwardDown
        val back = EventBridge.physicalBackDown

        if (brake != Brake.NONE) {
            val releasedForward = brake == Brake.FORWARD_RELEASED
            // The player pressed the key they had just released: they want to move again.
            val resumed = if (releasedForward) forward else back
            // Tick cap, wall clock (a stalled tick source must not hold a key), or momentum gone.
            val expired = tick > brakeUntilTick || now > brakeDeadlineNanos
            val sliding = StopTap.stillSliding(
                player.motionX, player.motionZ, player.rotation.yaw,
                releasedForward = releasedForward,
                minSpeed = minSpeed.toDouble()
            )
            if (resumed || expired || !sliding) release()
        } else if (player.health > 0f) {
            // Braking a player who is not moving would press a key for nothing — and "released W
            // while standing still" is the common case for a module like this.
            val forwardSlides = StopTap.stillSliding(
                player.motionX, player.motionZ, player.rotation.yaw,
                releasedForward = true, minSpeed = minSpeed.toDouble()
            )
            val backSlides = StopTap.stillSliding(
                player.motionX, player.motionZ, player.rotation.yaw,
                releasedForward = false, minSpeed = minSpeed.toDouble()
            )
            if (onWRelease && forwardSlides && prevForward && !forward && !back) {
                start(Brake.FORWARD_RELEASED, tick, now)
            } else if (onSRelease && backSlides && prevBack && !back && !forward) {
                start(Brake.BACK_RELEASED, tick, now)
            }
        }

        prevForward = forward
        prevBack = back
    }

    private fun start(which: Brake, tick: Int, now: Long) {
        brake = which
        brakeUntilTick = tick + maxTicks
        // One tick of slack past the window: ticks can be slower than 50 ms, and cutting a normal
        // brake short is worse than the backstop being lenient.
        brakeDeadlineNanos = now + (maxTicks + 1) * 50_000_000L
        if (which == Brake.FORWARD_RELEASED) EventBridge.claimBack(name, true)
        else EventBridge.claimForward(name, true)
        markWorked()
    }

    private fun release() {
        brake = Brake.NONE
        brakeUntilTick = -1
        brakeDeadlineNanos = 0L
        EventBridge.releaseBack(name)
        EventBridge.releaseForward(name)
    }

    override fun onEnable() {
        brake = Brake.NONE
        brakeUntilTick = -1
        brakeDeadlineNanos = 0L
        prevForward = EventBridge.physicalForwardDown
        prevBack = EventBridge.physicalBackDown
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        prevForward = false
        prevBack = false
        release()
    }
}
