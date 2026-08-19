package io.switchlite.adapter.common.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.ui.RenderUtils
import io.switchlite.adapter.common.ui.Theme

/**
 * Shared overlay rendering logic — version-agnostic.
 *
 * Draws the in-game HUD card and toast notifications using only
 * [GL11Bridge] and [FontRendererBridge] from the [RenderContext].
 * No Minecraft classes, no MappingContext, no reflection — pure rendering.
 *
 * NOTE: The in-game ClickGUI was removed in favor of a cross-version WebUI
 * panel. This overlay now renders ONLY the HUD (module status card) and
 * transient toasts. Configuration happens in the browser, not in-game.
 */
object OverlayRenderer {

    private const val CORNER_RADIUS = 8f
    private const val HUD_TITLE_BAR = 26

    fun render(ctx: RenderContext) {
        val g = ctx.gl

        // GL State: Save
        g.glPushAttrib(GLConstants.GL_ALL_ATTRIB_BITS)
        g.glMatrixMode(GLConstants.GL_PROJECTION)
        g.glPushMatrix()
        g.glMatrixMode(GLConstants.GL_MODELVIEW)
        g.glPushMatrix()

        try {
            // Setup 2D ortho (origin top-left, y-down)
            g.glMatrixMode(GLConstants.GL_PROJECTION)
            g.glLoadIdentity()
            g.glOrtho(0.0, ctx.scaledWidth.toDouble(), ctx.scaledHeight.toDouble(), 0.0, -1.0, 1.0)
            g.glMatrixMode(GLConstants.GL_MODELVIEW)
            g.glLoadIdentity()

            g.glDisable(GLConstants.GL_DEPTH_TEST)
            g.glDisable(GLConstants.GL_LIGHTING)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)

            // Explicit GL preconditions for the vanilla FontRenderer.
            g.glEnable(GLConstants.GL_TEXTURE_2D)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
            g.glDisable(GLConstants.GL_DEPTH_TEST)

            drawHudCard(ctx)
            drawToasts(ctx)

        } finally {
            // GL State: Restore — ALWAYS, even on error
            g.glMatrixMode(GLConstants.GL_PROJECTION)
            g.glPopMatrix()
            g.glMatrixMode(GLConstants.GL_MODELVIEW)
            g.glPopMatrix()
            g.glPopAttrib()
        }
    }

    // ── HUD card ──

    private fun drawHudCard(ctx: RenderContext) {
        val font = ctx.fontRenderer
        if (!HUD.enabled) return

        val entries = HUD.sortedEntries()
        val lineHeight = font.fontHeight + 3
        val pad = 8
        val barW = 3

        // Measure card.
        var maxTextW = font.getStringWidth("SwitchLite")
        for (e in entries) {
            maxTextW = maxOf(maxTextW, font.getStringWidth(e.name))
        }
        val cardW = pad * 2 + barW + 6 + maxTextW + 12
        val cardH = HUD_TITLE_BAR + pad + entries.size * lineHeight + pad

        // Drag — must run before drawing so pos updates apply this frame.
        HUD.handleMouseInput(
            EventBridge.guiMouseX, EventBridge.guiMouseY, EventBridge.guiLeftMouseDown,
            ctx.scaledWidth, ctx.scaledHeight, cardW, cardH
        )

        val x = HUD.posX
        val y = HUD.posY
        val factor = HUD.brightnessFactor()
        val fx = x.toFloat()
        val fy = y.toFloat()
        val fw = cardW.toFloat()
        val fh = cardH.toFloat()

        // Aurora depth: soft shadow + border + top highlight.
        RenderUtils.shadow(ctx, fx, fy, fw, fh, CORNER_RADIUS, depth = 4)
        RenderUtils.roundedRect(
            ctx, fx, fy, fw, fh, CORNER_RADIUS,
            RenderUtils.withAlpha(Theme.HUD_BG, factor.coerceAtMost(1f))
        )
        RenderUtils.roundedRectOutline(
            ctx, fx, fy, fw, fh, CORNER_RADIUS,
            Theme.BORDER, 1f, RenderUtils.withAlpha(Theme.HUD_BG, factor.coerceAtMost(1f))
        )
        RenderUtils.rect(
            ctx, fx + 6, fy + 2, fw - 12, 1f, Theme.TOP_HIGHLIGHT
        )

        // Title bar with accent underline.
        font.drawStringWithShadow(
            "SwitchLite", x + pad + barW + 4, y + 6,
            Theme.shade(Theme.TEXT, factor)
        )
        RenderUtils.rect(
            ctx, (x + pad).toFloat(), (y + HUD_TITLE_BAR - 4).toFloat(),
            (cardW - pad * 2).toFloat(), 1f, Theme.withAlpha(Theme.ACCENT, 0.35f * factor)
        )

        // Rows: [3px status bar] [module name]
        var ry = y + HUD_TITLE_BAR + pad
        for ((idx, entry) in entries.withIndex()) {
            val color = Theme.shade(HUD.entryColor(idx, entry), factor)
            RenderUtils.rect(
                ctx, (x + pad).toFloat(), (ry + 1).toFloat(),
                barW.toFloat(), (lineHeight - 2).toFloat(), color
            )
            font.drawStringWithShadow(entry.name, x + pad + barW + 6, ry, color)
            ry += lineHeight
        }
    }

    // ── Toasts ──

    private fun drawToasts(ctx: RenderContext) {
        val font = ctx.fontRenderer
        val notifications = EventBridge.drainNotifications()
        if (notifications.isEmpty()) return

        var notifY = ctx.scaledHeight - 12
        for (notif in notifications) {
            val color = when (notif.type) {
                EventBridge.NotificationType.SUCCESS -> Theme.ACCENT
                EventBridge.NotificationType.ERROR -> Theme.ERROR
                EventBridge.NotificationType.INFO -> Theme.WARN
            }
            val text = notif.text
            val textWidth = font.getStringWidth(text)
            val w = textWidth + 14
            val h = font.fontHeight + 8

            val tx = ctx.scaledWidth - w - 8
            val ty = notifY - h

            // Toast card (bottom-right, rounded)
            RenderUtils.roundedRect(
                ctx, tx.toFloat(), ty.toFloat(), w.toFloat(), h.toFloat(),
                CORNER_RADIUS, Theme.withAlpha(Theme.PANEL_BG, 0.85f)
            )
            // Left accent bar
            RenderUtils.rect(ctx, tx.toFloat(), (ty + 2).toFloat(), 2f, (h - 4).toFloat(), color)

            font.drawStringWithShadow(text, tx + 8, ty + 4, color)
            notifY = ty - 6
        }
    }
}
