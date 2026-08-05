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

            // Explicit GL preconditions for the vanilla FontRenderer.
            // MC's FontRenderer assumes TEXTURE_2D enabled + BLEND active;
            // calling it with leftover scene-render state (TEXTURE_2D off)
            // can bind a blank texture and corrupt later GUI rendering
            // ('checkerboard' panels on Create World / language screens).
            g.glEnable(GLConstants.GL_TEXTURE_2D)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
            g.glDisable(GLConstants.GL_DEPTH_TEST)

            // ══════════════════════════════════════
            //  Draw HUD
            // ══════════════════════════════════════
            val hudEntries = io.switchlite.adapter.common.module.render.HUD.hudEntries
            var hudY = 4
            if (hudEntries.isNotEmpty()) {
                // Multi-line HUD — one module per row (no endless single line)
                font.drawStringWithShadow("SwitchLite", 4, hudY, 0xFFFFFF)
                hudY += font.fontHeight + 2
                for (entry in hudEntries) {
                    val color = if (entry.isRed) 0xFF5555 else 0xFFFFFF
                    font.drawStringWithShadow(entry.name, 8, hudY, color)
                    hudY += font.fontHeight + 2
                }
            } else {
                val hudText = EventBridge.hudTextLine
                if (hudText.isNotEmpty()) {
                    font.drawStringWithShadow(hudText, 4, hudY, 0xFFFFFF)
                    hudY += font.fontHeight + 2
                }
            }
            if (EventBridge.isGuiOpen) {
                font.drawStringWithShadow("\u00A7a[GUI Open] \u00A77RShift to close", 4, hudY + 2, 0x00FF00)
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
        val lineHeight = font.fontHeight + 3
        val padding = 6
        val clickGui = io.switchlite.adapter.common.module.render.ClickGUI

        // One draggable panel per category
        for ((cat, rows) in clickGui.categoriesWithRows(lineHeight)) {
            val pos = clickGui.panelPos(cat)
            val panelHeight = clickGui.panelHeight(cat, lineHeight)
            drawRect(ctx, pos.x - padding, pos.y - padding, pos.x + clickGui.PANEL_WIDTH, pos.y + panelHeight, 0x80000000.toInt())

            // Title bar (draggable)
            font.drawStringWithShadow("\u00A76${cat.name} \u00A77\u00A7o[drag]", pos.x, pos.y, 0xFFFF55)

            // Module rows (shared rects with ClickGUI hit-testing)
            for (row in rows) {
                // Hover highlight
                val mx = EventBridge.guiMouseX
                val my = EventBridge.guiMouseY
                if (mx >= row.x && mx < row.x + row.width && my >= row.y && my < row.y + row.height) {
                    drawRect(ctx, row.x - 4, row.y - 1, row.x + row.width + 4, row.y + row.height + 1, 0x30FFFFFF)
                }
                val stateColor = if (row.module.enabled) 0x55FF55 else 0xAAAAAA
                val stateText = if (row.module.enabled) "[ON] " else "[OFF]"
                font.drawStringWithShadow("$stateText${row.module.name}", row.x, row.y, stateColor)
            }
        }
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
