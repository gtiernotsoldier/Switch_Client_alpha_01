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
 * The look input is `MouseHelper.deltaX/deltaY`, the same source the reference clients read: the mouse
 * turns the camera, the body keeps its rotation, and the two never get tangled.
 */
object Freelook : Module("Freelook", Category.VISUAL) {

    private val sensitivity by float("Sensitivity", 1.0f, 0.20f..3.00f)

    /** Flip this if looking up should feel like pushing the mouse down. */
    private val invertY by boolean("InvertY", false)

    private var freeYaw = 0f
    private var freePitch = 0f
    private var realYaw: Float? = null
    private var realPitch: Float? = null

    private val tickListener: (PlayerState, TargetState?) -> Unit = { player, _ ->
        if (enabled) onTick(player)
    }

    private fun onTick(player: PlayerState) {
        val yawNow = player.rotation.yaw
        val pitchNow = player.rotation.pitch

        // The first tick after enabling remembers where the body was pointing; that is also the rotation
        // the player gets back on disable. The camera angles START there and are then advanced by the
        // platform each frame — this tick must not write them again, or it would reset 20 times a second
        // the very accumulation that makes the rotation glide.
        if (realYaw == null) {
            realYaw = yawNow
            realPitch = pitchNow
            freeYaw = yawNow
            freePitch = pitchNow
            EventBridge.freeLookYaw = yawNow
            EventBridge.freeLookPitch = pitchNow
        }

        // The angles are accumulated by the platform once per FRAME (see ForgeEventBridge.applyMouseDelta)
        // — this tick is 20 Hz and the camera is drawn every frame, so accumulating here is what made the
        // rotation step instead of glide. The module only decides the rate and hands its options over.
        EventBridge.freelookSensitivity = sensitivity
        EventBridge.freelookInvertY = invertY

        // Safety net: if vanilla already applied the same delta before we took it, put the body back.
        val ry = realYaw ?: yawNow
        val rp = realPitch ?: pitchNow
        if (yawNow != ry || pitchNow != rp) {
            EventBridge.setPlayerRotation(ry, rp)
        }
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
    }
}
