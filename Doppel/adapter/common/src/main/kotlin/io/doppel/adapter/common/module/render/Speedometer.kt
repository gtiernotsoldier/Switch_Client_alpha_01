package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.render.RenderContext
import kotlin.math.sqrt

/**
 * Speedometer — a lightweight plain-number HUD showing:
 *   - current player horizontal speed (blocks/second)
 *   - speed retention rate (%) vs a sprint baseline
 *
 * Purpose: visually confirm whether KeepSprint (or vanilla attack slowdown) is actually
 * holding player speed during rapid attacks. Vanilla MC drops horizontal speed to ~60% while
 * attacking; with KeepSprint the retention should read much higher.
 *
 * Rendered exactly like the Keystrokes HUD: draggable while a GUI is open, plain text with
 * shadow, NO background box — just numbers.
 */
object Speedometer : Module("Speedometer", Category.RENDER) {

    /** Sprint baseline (blocks/second) used for the 100% retention reference. ~5.6 b/s. */
    private const val SPRINT_BPS = 5.6f

    @Volatile
    var posX: Int = 8
        private set
    @Volatile
    var posY: Int = 200
        private set

    /** Widget scale factor. */
    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    // Draggable state (same as Keystrokes).
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // Latest captured values (updated on the background tick thread, read on render thread).
    @Volatile private var speedBps: Float = 0f
    @Volatile private var retentionPct: Float = 0f
    @Volatile private var sprinting = false

    // Previous-tick position tracking for displacement-based speed measurement.
    private var prevPosX = 0.0
    private var prevPosZ = 0.0
    private var prevPosTick = -1L

    /** Low-pass filter state to smooth out per-tick position jitter. */
    private var smoothedSpeed = 0f

    private val tickListener: (io.doppel.core.model.PlayerState, io.doppel.core.model.TargetState?) -> Unit = { p, _ ->
        if (enabled) {
            // Measure speed from POSITION DISPLACEMENT over time, NOT Entity.motionX/Z.
            // field_70159_w (motionX) decays instantly each tick and reads ~walk speed even when
            // sprinting, so it under-reports. Displacement is the actual distance moved and is
            // reliable regardless of sprint.
            val t = System.nanoTime()
            if (prevPosTick != -1L) {
                val dtSec = (t - prevPosTick) / 1_000_000_000.0
                if (dtSec > 0.0) {
                    val dx = p.position.x - prevPosX
                    val dz = p.position.z - prevPosZ
                    val dist = sqrt(dx * dx + dz * dz)
                    val instBps = (dist / dtSec).toFloat()
                    // EMA low-pass: smooths the jitter from reading an integer-ish position each
                    // tick. First sample just takes the raw value; afterwards blend 30% new.
                    smoothedSpeed = if (smoothedSpeed <= 0f) instBps else smoothedSpeed * 0.7f + instBps * 0.3f
                    speedBps = smoothedSpeed
                    retentionPct = if (SPRINT_BPS > 0f) ((speedBps / SPRINT_BPS) * 100f).coerceIn(0f, 300f) else 0f
                }
            }
            prevPosX = p.position.x
            prevPosZ = p.position.z
            prevPosTick = t
            sprinting = p.isSprinting
        }
    }

    fun render(ctx: RenderContext) {
        if (!enabled) return
        handleDrag(ctx)
        draw(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drag (only while a GUI screen is open / paused)
    // ═══════════════════════════════════════════

    private fun handleDrag(ctx: RenderContext) {
        if (!EventBridge.isGuiOpen) {
            dragging = false
            return
        }
        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY
        val leftDown = EventBridge.guiLeftMouseDown
        val w = widgetWidth(ctx)
        val h = widgetHeight(ctx)

        if (leftDown) {
            if (!dragging) {
                if (mx in posX until (posX + w) && my in posY until (posY + h)) {
                    dragging = true
                    dragOffsetX = mx - posX
                    dragOffsetY = my - posY
                }
            } else {
                posX = mx - dragOffsetX
                posY = my - dragOffsetY
                clampToScreen(ctx)
            }
        } else {
            dragging = false
        }
    }

    private fun clampToScreen(ctx: RenderContext) {
        if (posX < 0) posX = 0
        if (posY < 0) posY = 0
        if (posX + widgetWidth(ctx) > ctx.scaledWidth) posX = ctx.scaledWidth - widgetWidth(ctx)
        if (posY + widgetHeight(ctx) > ctx.scaledHeight) posY = ctx.scaledHeight - widgetHeight(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drawing — plain text, no background
    // ═══════════════════════════════════════════

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        val maxLine = listOf(speedText(), retentionText(), sprintText()).maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 60
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight * 3 + 4) * scale).toInt()
    }

    private fun speedText(): String = "Speed %.2f b/s".format(speedBps)
    private fun retentionText(): String = "Retain %.1f%%".format(retentionPct)
    private fun sprintText(): String = if (sprinting) "Sprint" else "Walk"

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val g = ctx.gl
        val lineH = f.fontHeight + 2

        val color = if (sprinting) 0xFF7A00 else 0xFFFFFF

        // Apply the Scale option for real: scale the modelview, draw at posX/scale so the text
        // lands at posX..posX+width*scale (matching the drag hitbox).
        g.glPushMatrix()
        g.glScalef(scale, scale, 1f)
        val x = (posX / scale).toInt()
        val y = (posY / scale).toInt()
        f.drawStringWithShadow(speedText(), x, y, color)
        f.drawStringWithShadow(retentionText(), x, y + lineH, color)
        f.drawStringWithShadow(sprintText(), x, y + lineH * 2, 0xC0C0C0)
        g.glPopMatrix()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        prevPosTick = -1L
        smoothedSpeed = 0f
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        speedBps = 0f
        retentionPct = 0f
        sprinting = false
        prevPosTick = -1L
        smoothedSpeed = 0f
    }
}
