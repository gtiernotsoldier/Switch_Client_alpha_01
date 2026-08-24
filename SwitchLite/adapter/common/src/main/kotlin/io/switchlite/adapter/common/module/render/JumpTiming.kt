package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.core.model.PlayerState
import io.switchlite.core.model.TargetState
import io.switchlite.core.model.VelocityContext
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext

/**
 * JumpTiming — per-hit jump-reset timing quality + success rate.
 *
 * The old version tallied every MANUAL jump-key press, so repeated/bunny-hop presses polluted
 * the statistics. New rule (user's call): only a HIT while SPRINTING opens a timing window, and
 * each window counts AT MOST ONE outcome — a jump key press (manual OR the JumpReset module's
 * pulse) inside the window. Repeated presses within the same window never count twice.
 *
 *   - GREEN  : jump within [GREEN_MS] (0..100ms) of the knockback — ideal jump reset.
 *   - YELLOW : jump within [YELLOW_MS] (100..250ms) — late but usable.
 *   - WHITE  : jump pressed later than [YELLOW_MS] but inside the window — counted, poor timing.
 *   - MISS   : no jump inside the window — the hit was not reset.
 *
 * Statistics: `hits` = sprinting-hit windows closed, `success` = windows that ended with a jump.
 * Rate = success / hits. A hit while NOT sprinting is ignored entirely (no count).
 *
 * JumpReset adaptation: the module's queued jump pulse also presses the jump key, so its jumps
 * are counted as legitimate successes (the module IS the jump reset), and the status is tagged
 * "(JR)" when the pulse was the source — so the user can tell module-driven from manual timing.
 *
 * The knockback timestamp comes from the velocity notifier (Netty thread, precise), not the 20Hz
 * tick sample — the window opens the moment the S12/S27 arrives.
 */
object JumpTiming : Module("JumpTiming", Category.RENDER) {

    /** Green window: jump pressed within this many ms of the knockback. */
    private const val GREEN_MS = 100L
    /** Yellow window: up to this many ms after the knockback. */
    private const val YELLOW_MS = 250L
    /** A hit window stays open for this long waiting for a jump, then closes as a MISS. */
    private const val WINDOW_MS = 500L

    private const val COLOR_GREEN = 0x00C853
    private const val COLOR_YELLOW = 0xFFD600
    private const val COLOR_WHITE = 0xFFFFFF

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

    // Timing + stats state (written on Netty/tick threads, read on render thread)
    @Volatile private var status: String = "JT --"
    @Volatile private var color = COLOR_WHITE
    // Written by BOTH the Netty thread (hit-window close) and the tick thread (count/miss) —
    // use atomics so the counters never lose an increment to a read-modify-write race.
    private val hits = java.util.concurrent.atomic.AtomicInteger(0)
    private val success = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var windowOpen = false
    @Volatile private var windowStartNano = 0L
    @Volatile private var windowCounted = false

    // ========== Hit window opener (Netty thread — precise knockback time) ==========
    private val velocityNotifier: (VelocityContext) -> Unit = { ctx ->
        if (enabled) {
            // User rule: only a hit while sprinting opens a window (and can count a success).
            if (ctx.player.isSprinting) {
                // A new hit while the previous window is still open: close it as a miss.
                if (windowOpen && !windowCounted) {
                    hits.incrementAndGet()
                    status = "JT MISS"
                    color = COLOR_WHITE
                }
                windowOpen = true
                windowStartNano = System.nanoTime()
                windowCounted = false
                status = "JT HIT"
                color = COLOR_WHITE
            }
        }
    }

    // ========== Window watcher (background 20Hz tick) ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled && windowOpen && !windowCounted) {
            if (EventBridge.isKeyJumpDown) {
                // Count exactly once per window — repeated presses never re-count.
                windowCounted = true
                windowOpen = false
                hits.incrementAndGet()
                success.incrementAndGet()
                val delayMs = (System.nanoTime() - windowStartNano) / 1_000_000L
                val fromModule = EventBridge.isJumpPulseActive()
                val tag = if (fromModule) " (JR)" else ""
                when {
                    delayMs <= GREEN_MS -> {
                        status = "JT OK ${delayMs}ms$tag"
                        color = COLOR_GREEN
                    }
                    delayMs <= YELLOW_MS -> {
                        status = "JT LATE ${delayMs}ms$tag"
                        color = COLOR_YELLOW
                    }
                    else -> {
                        status = "JT LATE ${delayMs}ms$tag"
                        color = COLOR_WHITE
                    }
                }
            } else if (System.nanoTime() - windowStartNano > WINDOW_MS * 1_000_000L) {
                windowOpen = false
                hits.incrementAndGet()
                status = "JT MISS"
                color = COLOR_WHITE
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
        val h = hits.get()
        if (h == 0) return "JT rate --"
        val pct = (success.get() * 100f / h).toInt()
        return "JT ${success.get()}/$h ${pct}%"
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
        hits.set(0); success.set(0)
        windowOpen = false; windowCounted = false
        status = "JT --"; color = COLOR_WHITE
        EventBridge.registerVelocityNotifier(velocityNotifier)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityNotifier(velocityNotifier)
        EventBridge.unregisterTickListener(tickListener)
        windowOpen = false
    }
}
