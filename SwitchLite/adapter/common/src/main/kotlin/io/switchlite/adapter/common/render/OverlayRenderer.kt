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
    private var renderEntryDiagLogged = false

    fun render(ctx: RenderContext) {
        val g = ctx.gl

        // GL State: Save
        g.glPushAttrib(GLConstants.GL_ALL_ATTRIB_BITS)
        g.glMatrixMode(GLConstants.GL_PROJECTION)
        g.glPushMatrix()
        g.glMatrixMode(GLConstants.GL_MODELVIEW)
        g.glPushMatrix()

        try {
            if (!renderEntryDiagLogged) {
                renderEntryDiagLogged = true
                io.switchlite.core.logging.CoreLogger.info("[Overlay.render] entered. HUD.enabled=${HUD.enabled}, entries=${HUD.hudEntries.size}")
            }
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
            drawKeystrokes(ctx)
            drawSpeedometer(ctx)
            drawVelocityDisplay(ctx)
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

        val entries = HUD.hudEntries
        if (entries.isEmpty()) {
            if (!hudDiagLogged) { io.switchlite.core.logging.CoreLogger.warn("[Overlay] drawHudCard: no entries (HUD enabled=${HUD.enabled})"); hudDiagLogged = true }
            return
        }
        if (!hudDiagLogged) {
            io.switchlite.core.logging.CoreLogger.info("[Overlay] drawHudCard: ${entries.size} entries, font=${font::class.simpleName}, enabled=${HUD.enabled}")
            hudDiagLogged = true
        }

        val lineHeight = font.fontHeight + 4
        val left = HUD.position != "Right"
        val margin = HUD.posX
        val rightEdge = ctx.scaledWidth - margin
        val breathe = ((System.currentTimeMillis() % 3600L) / 3600f).let {
            if (it < 0.5f) it * 2f else 2f - it * 2f
        }

        // ── Per-module independent bars (no enclosing panel) ──
        var y = HUD.posY
        for (entry in entries) {
            val isTitle = entry.name == "SwitchLite"
            // Measure this row's width (name + value).
            var rowW = font.getStringWidth(entry.name) + 20
            if (entry.value.isNotEmpty()) rowW += 8 + font.getStringWidth(entry.value)
            val rowH = (lineHeight + 2).toFloat()

            val rowX = if (left) margin.toFloat() else (rightEdge - rowW).toFloat()
            val rowYf = y.toFloat()

            // Individual rounded bar (no enclosing panel).
            if (entry.isRed) {
                RenderUtils.glow(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0xFFFF5A5A.toInt(), spread = 4f, layers = 3)
                RenderUtils.roundedRect(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x381A0A0A.toInt())
                RenderUtils.roundedRectOutline(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x4AFF4D4D.toInt(), 1f, 0x381A0A0A.toInt())
            } else if (isTitle) {
                RenderUtils.roundedRect(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x2EFFFFFF.toInt())
                RenderUtils.roundedRectOutline(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x4AFF7A00.toInt(), 1f, 0x2EFFFFFF.toInt())
            } else {
                RenderUtils.roundedRect(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x0EFFFFFF.toInt())
                RenderUtils.roundedRectOutline(ctx, rowX, rowYf, rowW.toFloat(), rowH, 8f, 0x12FFFFFF.toInt(), 1f, 0x0EFFFFFF.toInt())
            }

            // Text: name, value right after it.
            val nameColor = when {
                isTitle -> Theme.ACCENT
                entry.isRed -> Theme.ERROR
                else -> Theme.TEXT_DIM
            }
            val tx = (rowX + 10).toInt()
            font.drawStringWithShadow(entry.name, tx, y, nameColor)
            if (entry.value.isNotEmpty()) {
                val vc = if (entry.highlight) RenderUtils.withAlpha(Theme.ACCENT, 0.75f + 0.25f * breathe) else Theme.TEXT_DIM
                font.drawStringWithShadow(entry.value, tx + font.getStringWidth(entry.name) + 8, y, vc)
            }
            y += lineHeight
        }
    }

    // ── Keystrokes (in-game key press indicator) ──

    private fun drawKeystrokes(ctx: RenderContext) {
        try {
            io.switchlite.adapter.common.module.render.Keystrokes.render(ctx)
        } catch (_: Exception) {}
    }

    // ── Speedometer (plain-number speed HUD) ──

    private fun drawSpeedometer(ctx: RenderContext) {
        try {
            io.switchlite.adapter.common.module.render.Speedometer.render(ctx)
        } catch (_: Exception) {}
    }

    // ── VelocityDisplay (3D motion readout, colors when velocity was modified) ──

    private fun drawVelocityDisplay(ctx: RenderContext) {
        try {
            io.switchlite.adapter.common.module.render.VelocityDisplay.render(ctx)
        } catch (_: Exception) {}
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
