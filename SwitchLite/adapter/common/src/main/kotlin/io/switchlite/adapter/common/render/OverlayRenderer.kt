package io.switchlite.adapter.common.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.ModuleRegistry

/**
 * Shared overlay rendering logic — version-agnostic.
 *
 * Draws HUD text, ClickGUI panel, and notifications using only
 * [GL11Bridge] and [FontRendererBridge] from the [RenderContext].
 * No Minecraft classes, no MappingContext, no reflection — pure rendering.
 *
 * Each version bootstrap:
 * 1. Constructs a [RenderContext] with its platform-specific bridges
 * 2. Calls [OverlayRenderer.render] with that context
 *
 * This ensures that a rendering bug found in one version is fixed
 * for all versions — no code duplication, no drift.
 */
object OverlayRenderer {

    fun render(ctx: RenderContext) {
        val g = ctx.gl
        val font = ctx.fontRenderer

        // ══════════════════════════════════════
        //  GL State: Save
        // ══════════════════════════════════════
        g.glPushAttrib(GLConstants.GL_ALL_ATTRIB_BITS)
        g.glMatrixMode(GLConstants.GL_PROJECTION)
        g.glPushMatrix()
        g.glMatrixMode(GLConstants.GL_MODELVIEW)
        g.glPushMatrix()

        try {
            // ══════════════════════════════════════
            //  Setup 2D ortho (origin top-left, y-down)
            // ══════════════════════════════════════
            g.glMatrixMode(GLConstants.GL_PROJECTION)
            g.glLoadIdentity()
            g.glOrtho(0.0, ctx.scaledWidth.toDouble(), ctx.scaledHeight.toDouble(), 0.0, -1.0, 1.0)
            g.glMatrixMode(GLConstants.GL_MODELVIEW)
            g.glLoadIdentity()

            // Disable 3D
            g.glDisable(GLConstants.GL_DEPTH_TEST)
            g.glDisable(GLConstants.GL_LIGHTING)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)

            // ══════════════════════════════════════
            //  Draw HUD
            // ══════════════════════════════════════
            val hudText = EventBridge.hudTextLine
            if (hudText.isNotEmpty()) {
                font.drawStringWithShadow(hudText, 4, 4, 0xFFFFFF)

                if (EventBridge.isGuiOpen) {
                    font.drawStringWithShadow("\u00A7a[GUI Open] \u00A77RShift to close", 4, 4 + font.fontHeight + 2, 0x00FF00)
                }
            }

            // Draw ClickGUI panel
            if (EventBridge.isGuiOpen) {
                drawClickGUI(ctx)
            }

            // Draw notifications
            val notifications = EventBridge.drainNotifications()
            if (notifications.isNotEmpty()) {
                var notifY = ctx.scaledHeight - notifications.size * (font.fontHeight + 4) - 4
                for (notif in notifications) {
                    val color = when (notif.type) {
                        EventBridge.NotificationType.SUCCESS -> 0x55FF55
                        EventBridge.NotificationType.ERROR -> 0xFF5555
                        EventBridge.NotificationType.INFO -> 0xFFFF55
                    }
                    val text = notif.text
                    val textWidth = font.getStringWidth(text)
                    font.drawStringWithShadow(text, ctx.scaledWidth - textWidth - 6, notifY, color)
                    notifY += font.fontHeight + 4
                }
            }

        } finally {
            // ══════════════════════════════════════
            //  GL State: Restore — ALWAYS, even on error
            // ══════════════════════════════════════
            g.glMatrixMode(GLConstants.GL_PROJECTION)
            g.glPopMatrix()
            g.glMatrixMode(GLConstants.GL_MODELVIEW)
            g.glPopMatrix()
            g.glPopAttrib()
        }
    }

    // ── ClickGUI panel ──

    private fun drawClickGUI(ctx: RenderContext) {
        val font = ctx.fontRenderer
        val categories = Category.values()
        val panelX = 40
        var panelY = 30
        val lineHeight = font.fontHeight + 3
        val padding = 6

        var totalHeight = padding * 2
        for (cat in categories) {
            totalHeight += lineHeight + 2
            val modules = ModuleRegistry.getByCategory(cat)
            totalHeight += modules.size * lineHeight
            totalHeight += 4
        }
        totalHeight += 20

        val panelWidth = 220
        drawRect(ctx, panelX - padding, panelY - padding, panelX + panelWidth, panelY + totalHeight, 0x80000000.toInt())

        for (cat in categories) {
            font.drawStringWithShadow("\u00A76${cat.name}", panelX, panelY, 0xFFFF55)
            panelY += lineHeight

            val modules = ModuleRegistry.getByCategory(cat)
            for (module in modules) {
                if (module.hidden) continue
                val stateColor = if (module.enabled) 0x55FF55 else 0xAAAAAA
                val stateText = if (module.enabled) "[ON] " else "[OFF]"
                font.drawStringWithShadow("$stateText${module.name}", panelX + 8, panelY, stateColor)
                panelY += lineHeight
            }
            panelY += 4
        }

        panelY += 8
        font.drawStringWithShadow("\u00A77Click modules to toggle | ESC to close", panelX, panelY, 0xAAAAAA)
    }

    // ── Filled rectangle ──

    /**
     * Draw a filled rectangle with the given color (ARGB format).
     *
     * IMPORTANT: This method does NOT restore GL state to "default" after drawing.
     * It relies on the caller's try-finally block in render() to restore the
     * full GL state via glPopAttrib. This avoids the bug where drawRect() was
     * disabling GL_BLEND and enabling GL_DEPTH_TEST, which broke subsequent
     * text rendering in the same frame.
     */
    private fun drawRect(ctx: RenderContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val g = ctx.gl
        g.glEnable(GLConstants.GL_BLEND)
        g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
        g.glDisable(GLConstants.GL_DEPTH_TEST)
        g.glDepthMask(false)
        g.glColor4f(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            ((color shr 24) and 0xFF) / 255f
        )
        g.glDisable(GLConstants.GL_TEXTURE_2D)
        g.glBegin(GLConstants.GL_QUADS)
        g.glVertex2f(x1.toFloat(), y2.toFloat())
        g.glVertex2f(x2.toFloat(), y2.toFloat())
        g.glVertex2f(x2.toFloat(), y1.toFloat())
        g.glVertex2f(x1.toFloat(), y1.toFloat())
        g.glEnd()
        g.glEnable(GLConstants.GL_TEXTURE_2D)
        g.glDepthMask(true)
    }
}
