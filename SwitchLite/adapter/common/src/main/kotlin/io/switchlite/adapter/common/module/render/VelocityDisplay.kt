package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext
import kotlin.math.sqrt

/**
 * VelocityDisplay — a plain-number HUD showing the player's 3D motion vector (motionX/Y/Z in
 * blocks/tick) and the horizontal resultant speed.
 *
 * Purpose: visually confirm whether the Velocity module is actually modifying knockback.
 * - When the last velocity packet was modified/cancelled by Velocity, the readout is drawn in the
 *   accent color (modified).
 * - When untouched (vanilla), it is drawn white.
 *
 * Rendered exactly like Speedometer: draggable while a GUI is open, plain text with shadow, NO
 * background box — vanilla font, per project conventions.
 */
object VelocityDisplay : Module("VelocityDisplay", Category.RENDER) {

    @Volatile
    var posX: Int = 8
        private set
    @Volatile
    var posY: Int = 240
        private set

    /** Widget scale factor. */
    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    // Draggable state (same as Speedometer/Keystrokes).
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // Latest captured values (updated on the background tick thread, read on render thread).
    @Volatile private var vx: Double = 0.0
    @Volatile private var vy: Double = 0.0
    @Volatile private var vz: Double = 0.0
    @Volatile private var modified: Boolean = false

    private val tickListener: (io.switchlite.core.model.PlayerState, io.switchlite.core.model.TargetState?) -> Unit = { p, _ ->
        if (enabled) {
            vx = p.motionX
            vy = p.motionY
            vz = p.motionZ
        }
    }

    fun render(ctx: RenderContext) {
        if (!enabled) return
        // Read the live "was velocity modified" flag each frame (set by the Velocity module on the
        // packet thread; stale within one tick is fine — it's a confirmation readout).
        modified = EventBridge.velocityModified
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

    private fun lines(): List<String> {
        val hSpeed = sqrt(vx * vx + vz * vz)
        val retainPct = if (EventBridge.lastKbOriginalSpeed > 0.001) {
            (EventBridge.lastKbModifiedSpeed / EventBridge.lastKbOriginalSpeed * 100).toInt()
        } else -1
        val line2 = if (retainPct >= 0) "KB ${retainPct}%/${100 - retainPct}%" else "Vel H %.2f".format(hSpeed)
        return listOf(
            "V %.2f %.2f %.2f".format(vx, vy, vz),
            line2
        )
    }

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        val maxLine = lines().maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 80
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight * 2 + 2) * scale).toInt()
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val x = posX
        val y = posY
        val lineH = f.fontHeight + 2

        // Accent when Velocity modified the last packet; white when vanilla.
        val color = if (modified) 0xFF7A00 else 0xFFFFFF

        val ls = lines()
        f.drawStringWithShadow(ls[0], x, y, color)
        f.drawStringWithShadow(ls[1], x, y + lineH, color)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        vx = 0.0; vy = 0.0; vz = 0.0
        modified = false
    }
}
