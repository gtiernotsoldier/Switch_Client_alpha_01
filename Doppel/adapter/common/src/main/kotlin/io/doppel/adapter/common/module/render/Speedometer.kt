package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.render.RenderContext
import kotlin.math.sqrt
import io.doppel.contract.model.PlayerState
import io.doppel.contract.model.TargetState

/**
 * Speedometer — a lightweight plain-number HUD showing:
 *   - current player horizontal speed (blocks/second)
 *   - speed retention rate (%) vs a sprint baseline
 *   - the sprint flag twice: ours (`p.isSprinting`) and the server's belief
 *     (`serverSprintState`)
 *   - how long those two have disagreed, or "sync"
 *
 * Purpose: visually confirm whether KeepSprint (or vanilla attack slowdown) is actually
 * holding player speed during rapid attacks. Vanilla MC drops horizontal speed to ~60% while
 * attacking; with KeepSprint the retention should read much higher.
 *
 * The two sprint flags are shown together because one of them alone is misleading: the local
 * flag reads "Sprint" in exactly the state MC-69459 describes, where the server has already
 * cleared its copy and is computing walking knockback. The DESYNC timer is that window's length.
 *
 * Rendered exactly like the Keystrokes HUD: draggable while a GUI is open, plain text with
 * shadow, NO background box — just numbers.
 */
object Speedometer : Module("Speedometer", Category.VISUAL) {

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
    /** What the server believes — the other half of the flag, and the half MC-69459 is about. */
    @Volatile private var serverSprinting = false
    /** nanoTime when the two flags started to disagree; 0 = in sync. */
    @Volatile private var desyncSinceNanos = 0L

    // Previous-tick position tracking for displacement-based speed measurement.
    private var prevPosX = 0.0
    private var prevPosZ = 0.0
    private var prevPosTick = -1L

    /** Low-pass filter state to smooth out per-tick position jitter. */
    private var smoothedSpeed = 0f

    private val tickListener: (io.doppel.contract.model.PlayerState, io.doppel.contract.model.TargetState?) -> Unit = { p, _ ->
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
            serverSprinting = EventBridge.serverSprintState
            // MC-69459 is exactly this inequality, so time it instead of only displaying it: the
            // span is the window in which the server is still paying the walking knockback while
            // the client believes it is sprinting.
            if (sprinting != serverSprinting) {
                if (desyncSinceNanos == 0L) desyncSinceNanos = System.nanoTime()
            } else {
                desyncSinceNanos = 0L
            }
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
        val maxLine = listOf(speedText(), retentionText(), syncText(), statusText())
            .maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 60
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        // 4 lines with a 2px gap between them.
        return ((ctx.fontRenderer.fontHeight * 4 + 6) * scale).toInt()
    }

    private fun speedText(): String = "Speed %.2f b/s".format(speedBps)
    private fun retentionText(): String = "Retain %.1f%%".format(retentionPct)

    /**
     * The two sprint flags side by side: the client's own (`p.isSprinting`) and the server's belief
     * (`EventBridge.serverSprintState`).
     *
     * Splitting them is the point. The old line showed the local flag alone, so it read "Sprint"
     * in exactly the state MC-69459 is about — the client sprinting while the server has already
     * cleared it and is computing walking knockback.
     */
    private fun syncText(): String =
        (if (sprinting) "L Sprint" else "L Walk") + "  " +
            (if (serverSprinting) "S Sprint" else "S Walk")

    /** How long the two have disagreed; "sync" when they agree. */
    private fun statusText(): String {
        val since = desyncSinceNanos
        if (since == 0L) return "sync"
        val ms = (System.nanoTime() - since) / 1_000_000
        return "DESYNC %.1fs".format(ms / 1000.0)
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val g = ctx.gl
        val lineH = f.fontHeight + 2

        val color = if (sprinting) 0xFF22D3EE.toInt() else 0xFFFFFF
        // The sync line is coloured by *disagreement*, not by sprint state: that is the only case
        // that carries information. Red = the server still thinks we walk while we sprint, which is
        // the walking-knockback window itself. Amber = the reverse, usually a lost or delayed C0B.
        val syncColor = when {
            sprinting && !serverSprinting -> 0xFFFF3B30.toInt()
            !sprinting && serverSprinting -> 0xFFFFD600.toInt()
            sprinting -> 0xFF22D3EE.toInt()
            else -> 0xC0C0C0
        }

        // Apply the Scale option for real: scale the modelview, draw at posX/scale so the text
        // lands at posX..posX+width*scale (matching the drag hitbox).
        g.glPushMatrix()
        g.glScalef(scale, scale, 1f)
        val x = (posX / scale).toInt()
        val y = (posY / scale).toInt()
        f.drawStringWithShadow(speedText(), x, y, color)
        f.drawStringWithShadow(retentionText(), x, y + lineH, color)
        f.drawStringWithShadow(syncText(), x, y + lineH * 2, syncColor)
        f.drawStringWithShadow(statusText(), x, y + lineH * 3, syncColor)
        g.glPopMatrix()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        prevPosTick = -1L
        smoothedSpeed = 0f
        desyncSinceNanos = 0L
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        speedBps = 0f
        retentionPct = 0f
        sprinting = false
        serverSprinting = false
        desyncSinceNanos = 0L
        prevPosTick = -1L
        smoothedSpeed = 0f
    }
}
