package io.doppel.adapter.common.module.player

import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.option.*
import io.doppel.adapter.common.render.RenderContext
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

    // ========== Bridging readout (Raven-bPLUS's SafeWalk) ==========

    /** Show how many placeable blocks are left, centred under the crosshair while bridging. */
    private val showBlockAmount by boolean("ShowBlockAmount", true)

    /** "Total" = every block in the hotbar + main inventory, "CurrentStack" = only the held stack. */
    private val blockShowMode by choices("BlockShowMode", arrayOf("Total", "CurrentStack"))

    /**
     * The block count, drawn the way the reference does it: plain text with a shadow, centred just
     * below the crosshair, coloured by how much is left so an emptying inventory is visible without
     * reading the number.
     *
     * Gated on the module being on — the module is what you enable when you bridge, which is the same
     * condition the reference calls "shouldBridge" — and on there being something to say: a 0 would
     * put "0 blocks" over the crosshair of everybody with an empty hand.
     */
    fun render(ctx: RenderContext) {
        if (!enabled || !showBlockAmount || EventBridge.isGuiOpen) return
        val total = EventBridge.blockCount(if (blockShowMode == "CurrentStack") 1 else 0)
        if (total <= 0) return
        val text = "$total blocks"
        val font = ctx.fontRenderer
        val x = ctx.scaledWidth / 2 - font.getStringWidth(text) / 2
        val y = ctx.scaledHeight / 2 + 15
        font.drawStringWithShadow(text, x, y, blockCountColor(total))
    }

    /** Raven-bPLUS's ladder: under 16 red, under 32 orange, under 128 yellow, otherwise green. */
    internal fun blockCountColor(total: Int): Int = when {
        total < 16 -> 0xFFFF5555.toInt()
        total < 32 -> 0xFFFFAA00.toInt()
        total < 128 -> 0xFFFFFF55.toInt()
        else -> 0xFF55FF55.toInt()
    }

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
            // The assist is only "working" while it is actually steering the look.
            markWorked()
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
