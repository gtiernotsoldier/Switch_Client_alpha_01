package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.float
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * Freelook — look around without turning.
 *
 * The camera follows [EventBridge.freeLookYaw] / [EventBridge.freeLookPitch] through a stand-in render
 * entity, so the player's own rotation never moves: walking direction, hit direction and everything the
 * server is told stay exactly where they were. That is the point of the module — a camera you can swing
 * around while the body keeps facing the way it faced.
 *
 * The mouse movement is recovered from the player's **own** rotation change: vanilla has already applied
 * this tick's mouse delta to the player by the time we are asked, and the player's rotation is then put
 * back every tick, so the body never turns while the camera does.
 */
object Freelook : Module("Freelook", Category.VISUAL) {

    private val sensitivity by float("Sensitivity", 1.0f, 0.20f..3.00f)

    /** Flip this if looking up should feel like pushing the mouse down. */
    private val invertY by boolean("InvertY", false)

    private var freeYaw = 0f
    private var freePitch = 0f
    private var realYaw: Float? = null
    private var realPitch: Float? = null

    /** The player's rotation as of the previous tick, used to recover this tick's mouse movement. */
    private var lastYaw: Float? = null
    private var lastPitch: Float? = null

    private val tickListener: (PlayerState, TargetState?) -> Unit = { player, _ ->
        if (enabled) onTick(player)
    }

    private fun onTick(player: PlayerState) {
        val yawNow = player.rotation.yaw
        val pitchNow = player.rotation.pitch

        // The first tick after enabling remembers where the body was pointing; that is also the rotation
        // the player gets back on disable.
        if (realYaw == null) {
            realYaw = yawNow
            realPitch = pitchNow
            freeYaw = yawNow
            freePitch = pitchNow
            lastYaw = yawNow
            lastPitch = pitchNow
        }

        // Recover the mouse movement from the PLAYER'S OWN rotation change, not from Mouse.getDX().
        // Vanilla has already applied this tick's mouse delta to the player before we are asked, and it
        // has already consumed the LWJGL delta doing so — reading Mouse.getDX() from a 20 Hz tick returns
        // 0, which is exactly how the first version ended up with a camera locked to its starting angle.
        val dYaw = wrapDegrees(yawNow - (lastYaw ?: yawNow))
        val dPitch = pitchNow - (lastPitch ?: pitchNow)
        lastYaw = yawNow
        lastPitch = pitchNow

        val turned = accumulate(freeYaw, freePitch, dYaw, dPitch, sensitivity, invertY)
        freeYaw = turned.first
        freePitch = turned.second

        // Put the body back where it was, so nothing but the camera ever moves.
        val ry = realYaw ?: yawNow
        val rp = realPitch ?: pitchNow
        if (yawNow != ry || pitchNow != rp) {
            EventBridge.setPlayerRotation(ry, rp)
        }

        EventBridge.freeLookYaw = freeYaw
        EventBridge.freeLookPitch = freePitch
    }

    /** Shortest signed difference, so crossing the +/-180 seam is a small step and not a full spin. */
    internal fun wrapDegrees(delta: Float): Float {
        var d = delta % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** The camera angles after this tick's mouse movement. Pure, so the arithmetic is testable. */
    internal fun accumulate(
        yaw: Float,
        pitch: Float,
        dx: Float,
        dy: Float,
        sensitivity: Float,
        invertY: Boolean
    ): Pair<Float, Float> {
        val newYaw = yaw + dx * sensitivity
        val dyApplied = dy * sensitivity * (if (invertY) 1f else -1f)
        val newPitch = (pitch + dyApplied).coerceIn(-90f, 90f)
        return newYaw to newPitch
    }


    override fun onEnable() {
        realYaw = null
        realPitch = null
        lastYaw = null
        lastPitch = null
        EventBridge.freeLookActive = true
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Turning the flag off is all it takes: the platform hands the camera back to the player, whose
        // rotation we never moved, so the view returns exactly where it was.
        EventBridge.freeLookActive = false
        realYaw = null
        realPitch = null
        lastYaw = null
        lastPitch = null
    }
}
