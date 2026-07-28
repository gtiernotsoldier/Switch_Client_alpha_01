package io.switchlite.adapter.common.module.player

import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.option.*
import kotlin.math.abs

/**
 * BridgeAssist — auto-align view angles for precise block placement.
 *
 * Detects when the player is on a block edge and smoothly rotates the
 * view to preset bridging angles (GodBridge, Moonwalk, Breezily, Normal).
 * Only corrects the camera — does not place blocks or move.
 *
 * Glide rotation simulates human smooth aim, avoiding instant-snap detection.
 */
object BridgeAssist : Module("BridgeAssist", Category.PLAYER) {

    private enum class Phase { IDLE, WAITING, GLIDING }

    // ========== Mode Presets ==========
    private data class Preset(
        val pitch: Float,
        val yaws: List<Float>
    )

    private val presets = mapOf(
        "GodBridge" to Preset(75.6f, listOf(-315f, -225f, -135f, -45f, 0f, 45f, 135f, 225f, 315f)),
        "Moonwalk"  to Preset(79.6f, listOf(-315f, -270f, -225f, -180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)),
        "Breezily"  to Preset(79.9f, listOf(-315f, -225f, -135f, -45f, 0f, 45f, 135f, 225f, 315f)),
        "Normal"    to Preset(78.0f, listOf(-315f, -225f, -135f, -45f, 0f, 45f, 135f, 225f, 315f))
    )

    // ========== Config ==========
    private val mode by choices("Mode", arrayOf("GodBridge", "Moonwalk", "Breezily", "Normal"))
    private val waitTime by int("WaitTime", 500, 0..5000, "ms")
    private val assistRange by int("AssistRange", 10, 1..40, "°")
    private val glideSpeed by int("GlideSpeed", 5, 1..20)
    private val setLook by boolean("SetLook", true)
    private val onlySneaking by boolean("OnlySneaking", true)
    private val workWithSafeWalk by boolean("WorkWithSafeWalk", false)

    // ========== State ==========
    private var phase = Phase.IDLE
    private var waitStartNano: Long = 0L
    private var fromYaw: Float = 0f
    private var fromPitch: Float = 0f
    private var toYaw: Float = 0f
    private var toPitch: Float = 0f
    private var glideProgress: Float = 0f

    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) onTick(p)
    }

    private fun onTick(player: PlayerState) {
        when (phase) {
            Phase.GLIDING -> glide(player)
            Phase.WAITING -> if (System.nanoTime() >= waitStartNano) startGlide(player)
            Phase.IDLE -> evaluate(player)
        }
    }

    private fun evaluate(player: PlayerState) {
        // Must be on edge
        if (!EventBridge.isOnBlockEdge()) return
        // Sneaking gate
        if (onlySneaking && !player.isSneaking) return
        // SafeWalk compatibility
        if (EventBridge.isSafeWalkEnabled && !workWithSafeWalk) return

        // Wait delay
        phase = Phase.WAITING
        waitStartNano = System.nanoTime() + waitTime * 1_000_000L
    }

    private fun startGlide(player: PlayerState) {
        val preset = presets[mode] ?: return

        // Pick closest yaw preset
        val normYaw = player.rotation.yaw % 360f
        val targetYaw = preset.yaws.minByOrNull { abs(normYaw - it) } ?: return

        val yawDiff = abs(normYaw - targetYaw)
        val pitchDiff = abs(player.rotation.pitch - preset.pitch)

        // Within assist range → start glide
        if (yawDiff > assistRange && pitchDiff > assistRange) {
            phase = Phase.IDLE
            return
        }

        fromYaw = player.rotation.yaw
        fromPitch = player.rotation.pitch
        toYaw = targetYaw
        toPitch = preset.pitch
        glideProgress = 0f
        phase = Phase.GLIDING
    }

    private fun glide(player: PlayerState) {
        if (!EventBridge.isOnBlockEdge()) {
            phase = Phase.IDLE
            return
        }

        glideProgress += glideSpeed * 0.02f  // roughly 1.0 per 50 ticks at speed=1
        if (glideProgress >= 1.0f) {
            glideProgress = 1.0f
            phase = Phase.IDLE
        }

        if (setLook) {
            val t = easeInOut(glideProgress)
            val yaw = fromYaw + (toYaw - fromYaw) * t
            val pitch = fromPitch + (toPitch - fromPitch) * t
            EventBridge.setPlayerRotation(yaw, pitch)
        }
    }

    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) 2f * t * t else -1f + (4f - 2f * t) * t
    }

    override fun onEnable() {
        phase = Phase.IDLE
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        phase = Phase.IDLE
    }
}
