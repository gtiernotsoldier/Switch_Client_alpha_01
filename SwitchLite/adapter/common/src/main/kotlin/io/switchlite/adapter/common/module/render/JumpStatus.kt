package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext

/**
 * JumpStatus — a tiny plain-number HUD showing the current jump key state.
 *
 * Purpose: visually confirm whether JumpReset (or anything else) actually pressed the jump key.
 * Reads EventBridge.isKeyJumpDown (the real keybinding pressed state, refreshed on the main thread
 * each frame by ForgeEventBridge.refreshKeyDisplayState). When the jump key is down (including a
 * JumpReset pulse), the text shows "Jump ON" in the accent color; otherwise "Jump OFF" in white.
 *
 * Rendered exactly like Speedometer: draggable while a GUI is open, plain text with shadow, NO
 * background box — vanilla font.
 */
object JumpStatus : Module("JumpStatus", Category.RENDER) {

    @Volatile
    var posX: Int = -1
        private set
    @Volatile
    var posY: Int = -1
        private set

    /** Widget scale factor. */
    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    // Draggable state (same as Speedometer/Keystrokes).
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    fun render(ctx: RenderContext) {
        if (!enabled) return
        // First render: center the widget on screen (posX/posY == -1 means "unplaced").
        if (posX < 0 || posY < 0) {
            posX = (ctx.scaledWidth - widgetWidth(ctx)) / 2
            posY = (ctx.scaledHeight - widgetHeight(ctx)) / 2
        }
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

    private fun text(): String = if (EventBridge.isKeyJumpDown) "Jump ON" else "Jump OFF"

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        return (f.getStringWidth(text()) * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight + 2) * scale).toInt()
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val color = if (EventBridge.isKeyJumpDown) 0xFF7A00 else 0xFFFFFF
        f.drawStringWithShadow(text(), posX, posY, color)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {}
    override fun onDisable() {}
}
