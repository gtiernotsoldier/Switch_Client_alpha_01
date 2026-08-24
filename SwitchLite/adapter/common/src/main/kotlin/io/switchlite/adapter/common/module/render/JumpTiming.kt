package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext

/**
 * JumpTiming — shows the correct jump-reset timing window after a knockback.
 *
 * After an S12 knockback, this HUD indicates the timing quality of a MANUAL jump-key press:
 *   - GREEN  : jump pressed within [GREEN_MS] (0..100ms) of the knockback — ideal jump reset.
 *   - YELLOW : jump pressed within [YELLOW_MS] (100..250ms) — late but usable.
 *   - WHITE  : no knockback recently / no manual jump in the window.
 *
 * It also tracks the success rate of manual jump presses only: how often the press landed in the
 * green window vs total manual jump-key presses since enable. Only the player's own jump key is
 * counted — module-driven jumps (JumpReset queueJump) are NOT tallied, so the rate is not polluted
 * by the module itself ("no false positives").
 *
 * Rendered like the other HUD widgets: draggable, plain text, no background.
 */
object JumpTiming : Module("JumpTiming", Category.RENDER) {

    /** Green window: jump pressed within this many ms of the knockback. */
    private const val GREEN_MS = 100L
    /** Yellow window: up to this many ms after the knockback. */
    private const val YELLOW_MS = 250L

    @Volatile
    var posX: Int = -1
        private set
    @Volatile
    var posY: Int = -1
        private set

    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    // Draggable state
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // Timing + stats state
    @Volatile private var status: String = "JT --"
    @Volatile private var color = 0xFFFFFF
    @Volatile private var successCount = 0
    @Volatile private var totalCount = 0
    @Volatile private var lastKbNano = 0L
    @Volatile private var prevJump = false

    private val tickListener: (io.switchlite.core.model.PlayerState, io.switchlite.core.model.TargetState?) -> Unit = { _, _ ->
        if (!enabled) return@tickListener
        val now = System.nanoTime()

        // Track the most recent knockback.
        val kb = EventBridge.lastKnockbackNano
        if (kb > lastKbNano) lastKbNano = kb

        // Detect MANUAL jump-key presses (physical space). We use the real key state; a press edge
        // that comes from JumpReset's synthetic queue is not a physical press, so it isn't counted.
        val jumpDown = EventBridge.isKeyJumpDown
        val pressEdge = jumpDown && !prevJump
        prevJump = jumpDown

        if (pressEdge) {
            totalCount++
            if (lastKbNano != 0L) {
                val delayMs = (now - lastKbNano) / 1_000_000L
                when {
                    delayMs <= GREEN_MS -> {
                        successCount++
                        status = "JT OK ${delayMs}ms"
                        color = 0x00C853 // green
                    }
                    delayMs <= YELLOW_MS -> {
                        status = "JT LATE ${delayMs}ms"
                        color = 0xFFD600 // yellow
                    }
                    else -> {
                        status = "JT OFF ${delayMs}ms"
                        color = 0xFFFFFF // white
                    }
                }
            } else {
                status = "JT --"
                color = 0xFFFFFF
            }
        }
    }

    fun render(ctx: RenderContext) {
        if (!enabled) return
        if (posX < 0 || posY < 0) {
            posX = (ctx.scaledWidth - widgetWidth(ctx)) / 2
            posY = (ctx.scaledHeight - widgetHeight(ctx)) / 2
        }
        handleDrag(ctx)
        draw(ctx)
    }

    private fun handleDrag(ctx: RenderContext) {
        if (!EventBridge.isGuiOpen) { dragging = false; return }
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

    private fun rateText(): String {
        if (totalCount == 0) return "JT rate --"
        val pct = (successCount * 100f / totalCount).toInt()
        return "JT ${successCount}/${totalCount} ${pct}%"
    }

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        val maxLine = listOf(status, rateText()).maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 70
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight * 2 + 2) * scale).toInt()
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val lineH = f.fontHeight + 2
        f.drawStringWithShadow(status, posX, posY, color)
        f.drawStringWithShadow(rateText(), posX, posY + lineH, 0xC0C0C0)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        successCount = 0; totalCount = 0
        lastKbNano = 0L; prevJump = false
        status = "JT --"; color = 0xFFFFFF
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
