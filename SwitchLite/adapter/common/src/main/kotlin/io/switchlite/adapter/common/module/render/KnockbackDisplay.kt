package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.RenderContext

/**
 * KnockbackDisplay — shows the player's knockback coefficient and the actual knockback distance.
 *
 * After an S12 knockback the HUD shows, compactly:
 *   - KB retain/cut % : how much horizontal speed was kept vs cut by Velocity
 *     (e.g. "KB 40/60" = kept 40%, cut 60%; "KB 100/0" = untouched/vanilla).
 *   - distance moved during the knockback (blocks), measured via position displacement from the
 *     moment the knockback landed until the player's speed settles.
 *
 * Rendered like the other HUD widgets: draggable, plain text, no background, compact width.
 */
object KnockbackDisplay : Module("KnockbackDisplay", Category.RENDER) {

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

    // Knockback distance tracking
    @Volatile private var kbDistance: Double = 0.0
    @Volatile private var measuring = false
    @Volatile private var startX = 0.0
    @Volatile private var startZ = 0.0
    @Volatile private var lastKbNano = 0L
    @Volatile private var lastPosX = 0.0
    @Volatile private var lastPosZ = 0.0

    private val tickListener: (io.switchlite.core.model.PlayerState, io.switchlite.core.model.TargetState?) -> Unit = { p, _ ->
        if (enabled) {
            val kb = EventBridge.lastKnockbackNano
            if (kb != lastKbNano) {
                // New knockback: start measuring displacement.
                lastKbNano = kb
                measuring = true
                startX = p.position.x
                startZ = p.position.z
                kbDistance = 0.0
            }
            if (measuring) {
                val dx = p.position.x - startX
                val dz = p.position.z - startZ
                kbDistance = kotlin.math.sqrt(dx * dx + dz * dz)
                // Stop measuring when the player is basically still (speed settled) OR after ~1.5s.
                val settled = kotlin.math.abs(p.motionX) < 0.001 && kotlin.math.abs(p.motionZ) < 0.001
                val nowNano = System.nanoTime()
                val elapsedMs = (nowNano - kb) / 1_000_000L
                if (settled || elapsedMs > 1500L) {
                    measuring = false
                }
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

    private fun lines(): List<String> {
        val orig = EventBridge.lastKbOriginalSpeed
        val mod = EventBridge.lastKbModifiedSpeed
        val kbLine = if (orig > 0.001) {
            val retain = (mod / orig * 100).toInt()
            "KB $retain/${100 - retain}"
        } else {
            "KB --/--"
        }
        return listOf(kbLine, "D %.2f".format(kbDistance))
    }

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        val maxLine = lines().maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 70
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight * 2 + 2) * scale).toInt()
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val lineH = f.fontHeight + 2
        val ls = lines()
        f.drawStringWithShadow(ls[0], posX, posY, 0xFFFFFF)
        f.drawStringWithShadow(ls[1], posX, posY + lineH, 0xC0C0C0)
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        measuring = false
        kbDistance = 0.0
        lastKbNano = 0L
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        measuring = false
        kbDistance = 0.0
    }
}
