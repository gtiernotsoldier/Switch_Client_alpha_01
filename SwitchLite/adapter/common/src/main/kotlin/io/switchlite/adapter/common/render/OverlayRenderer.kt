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

        val lineHeight = font.fontHeight + 5
        val left = HUD.position != "Right"
        val margin = HUD.posX
        val rightEdge = ctx.scaledWidth - margin
        // "Breathing": slow 3.6s pulse for the whole panel.
        val breathe = ((System.currentTimeMillis() % 3600L) / 3600f).let {
            if (it < 0.5f) it * 2f else 2f - it * 2f
        }
        val breathOffset = (breathe - 0.5f) * -1.5f

        // Measure the widest row (name + value) for a uniform panel width.
        var maxW = font.getStringWidth("SwitchLite")
        for (e in entries) {
            var w = font.getStringWidth(e.name)
            if (e.value.isNotEmpty()) w += 10 + font.getStringWidth(e.value)
            if (w > maxW) maxW = w
        }
        val panelW = (maxW + 34).toFloat()
        val panelH = (entries.size * lineHeight + 16).toFloat()
        val panelX = if (left) margin.toFloat() else (rightEdge - panelW).toFloat()
        // Clamp vertically so a long module list never runs off the screen.
        val rawPanelY = HUD.posY + breathOffset
        val maxPanelY = (ctx.scaledHeight - panelH).coerceAtLeast(0f)
        val panelY = rawPanelY.coerceIn(0f, maxPanelY).toFloat()

        // ── Whole glass panel: soft shadow + translucent gradient + border ──
        RenderUtils.shadow(ctx, panelX, panelY, panelW, panelH, 14f, depth = 4)
        RenderUtils.verticalGradient(
            ctx, panelX, panelY, panelW, panelH,
            RenderUtils.withAlpha(0x141018.toInt(), 0.78f + 0.05f * breathe),
            RenderUtils.withAlpha(0x0A0A12.toInt(), 0.86f + 0.04f * breathe),
            bands = 6
        )
        RenderUtils.roundedRectOutline(ctx, panelX, panelY, panelW, panelH, 14f, 0x26FFFFFF.toInt(), 1f, 0x00000000.toInt())
        // top highlight (glass reflection)
        RenderUtils.rect(ctx, panelX + 8, panelY + 1.5f, panelW - 16, 1f, 0x2EFFFFFF.toInt())
        // accent tint on the left edge (spine)
        RenderUtils.rect(ctx, panelX + 1, panelY + 8, 3f, panelH - 16, RenderUtils.withAlpha(0xFFFF7A00.toInt(), 0.35f + 0.20f * breathe))

        var y = (panelY + 10).toInt()
        for (entry in entries) {
            val isTitle = entry.name == "SwitchLite"
            val nameColor = when {
                isTitle -> Theme.ACCENT
                entry.isRed -> Theme.ERROR
                else -> Theme.TEXT_DIM   // disabled/plain modules are dimmer → clearer hierarchy
            }
            val rowH = (lineHeight - 1).toFloat()

            // Per-row glass bar — strong red glow when enabled, accent for title,
            // faint + dim for disabled so enabled modules stand out.
            val rowYf = y.toFloat()
            if (entry.isRed) {
                RenderUtils.glow(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0xFFFF5A5A.toInt(), spread = 5f, layers = 4)
                RenderUtils.roundedRect(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x381A0A0A.toInt())
                RenderUtils.roundedRectOutline(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x4AFF4D4D.toInt(), 1f, 0x381A0A0A.toInt())
            } else if (isTitle) {
                RenderUtils.glow(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0xFFFF7A00.toInt(), spread = 4f, layers = 3)
                RenderUtils.roundedRect(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x2EFFFFFF.toInt())
                RenderUtils.roundedRectOutline(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x4AFF7A00.toInt(), 1f, 0x2EFFFFFF.toInt())
            } else {
                // Disabled: very faint bar + dim text.
                RenderUtils.roundedRect(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x0EFFFFFF.toInt())
                RenderUtils.roundedRectOutline(ctx, panelX + 6, rowYf, panelW - 12, rowH, 8f, 0x12FFFFFF.toInt(), 1f, 0x0EFFFFFF.toInt())
            }

            // Text: name, then value right after it with a small fixed gap
            // (so the number is close to the name, not pushed to the far edge).
            val tx = (panelX + 14).toInt()
            font.drawStringWithShadow(entry.name, tx, y, nameColor)
            if (entry.value.isNotEmpty()) {
                val vc = if (entry.highlight) RenderUtils.withAlpha(Theme.ACCENT, 0.75f + 0.25f * breathe) else Theme.TEXT_DIM
                val valueX = tx + font.getStringWidth(entry.name) + 8
                font.drawStringWithShadow(entry.value, valueX, y, vc)
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
