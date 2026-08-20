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

    /** Diagnostic — log HUD visibility issue once, not every frame. */
    private var hudDiagLogged = false

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
        if (!HUD.enabled) {
            if (!hudDiagLogged) { io.switchlite.core.logging.CoreLogger.warn("[Overlay] drawHudCard: HUD disabled"); hudDiagLogged = true }
            return
        }

        // Transparent text list — no card background (SwitchLite HUD style).
        // One line per enabled module: "Name" + optional value; red when the
        // module is active, orange highlight for numeric values.
        val entries = HUD.hudEntries
        if (entries.isEmpty()) {
            if (!hudDiagLogged) { io.switchlite.core.logging.CoreLogger.warn("[Overlay] drawHudCard: no entries"); hudDiagLogged = true }
            return
        }

        val lineHeight = font.fontHeight + 3
        val left = HUD.position != "Right"
        val margin = HUD.posX
        // Right-aligned: value hugs the right edge, name sits to its left.
        val rightEdge = ctx.scaledWidth - margin
        // "Breathing": a slow time-based pulse for the highlight value text.
        val breathe = ((System.currentTimeMillis() % 2000L) / 2000f).let {
            if (it < 0.5f) it * 2f else 2f - it * 2f   // 0..1..0 triangle wave
        }

        var y = HUD.posY
        for (entry in entries) {
            val nameColor = if (entry.isRed) Theme.ERROR else Theme.TEXT
            if (left) {
                // Left: "Name" then "value" to the right.
                font.drawStringWithShadow(entry.name, margin, y, nameColor)
                if (entry.value.isNotEmpty()) {
                    val vc = if (entry.highlight) Theme.withAlpha(Theme.ACCENT, 0.6f + 0.4f * breathe) else Theme.TEXT_DIM
                    font.drawStringWithShadow(entry.value, margin + font.getStringWidth(entry.name) + 8, y, vc)
                }
            } else {
                // Right (mirror of left): row is right-aligned. "value" then "Name",
                // value hugs the right edge.
                val total = font.getStringWidth(entry.name) +
                    (if (entry.value.isNotEmpty()) font.getStringWidth(entry.value) + 8 else 0)
                var x = rightEdge - total
                if (entry.value.isNotEmpty()) {
                    val vc = if (entry.highlight) Theme.withAlpha(Theme.ACCENT, 0.6f + 0.4f * breathe) else Theme.TEXT_DIM
                    font.drawStringWithShadow(entry.value, x, y, vc)
                    x += font.getStringWidth(entry.value) + 8
                }
                font.drawStringWithShadow(entry.name, x, y, nameColor)
            }
            y += lineHeight
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
