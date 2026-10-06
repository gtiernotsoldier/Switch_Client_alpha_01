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
 * The mouse is read straight from LWJGL (`Mouse.getDX`/`getDY`, reflected because this module has no game
 * classes on its compile path) and the player's rotation is put back every tick, because vanilla applies
 * that same mouse delta to it: without the restoration the body would turn with the camera.
 */
object Freelook : Module("Freelook", Category.VISUAL) {

    private val sensitivity by float("Sensitivity", 0.15f, 0.05f..0.60f)

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
        // The first tick after enabling remembers where the body was pointing; from then on that is put
        // back every tick, so it is also the rotation the player has when the module goes off.
        if (realYaw == null) {
            realYaw = player.rotation.yaw
            realPitch = player.rotation.pitch
            freeYaw = player.rotation.yaw
            freePitch = player.rotation.pitch
        }

        val (dx, dy) = mouseDelta()
        val turned = accumulate(freeYaw, freePitch, dx, dy, sensitivity, invertY)
        freeYaw = turned.first
        freePitch = turned.second

        // Put the body back where it was: vanilla just applied the same mouse delta to it.
        val ry = realYaw ?: player.rotation.yaw
        val rp = realPitch ?: player.rotation.pitch
        if (player.rotation.yaw != ry || player.rotation.pitch != rp) {
            EventBridge.setPlayerRotation(ry, rp)
        }

        EventBridge.freeLookYaw = freeYaw
        EventBridge.freeLookPitch = freePitch
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

    /** LWJGL's per-poll mouse delta; (0,0) when LWJGL is not there (unit tests, headless). */
    private fun mouseDelta(): Pair<Float, Float> = try {
        val mouse = Class.forName("org.lwjgl.input.Mouse")
        val dx = mouse.getMethod("getDX").invoke(null) as? Int ?: 0
        val dy = mouse.getMethod("getDY").invoke(null) as? Int ?: 0
        dx.toFloat() to dy.toFloat()
    } catch (_: Throwable) {
        0f to 0f
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
