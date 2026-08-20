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

        val lineHeight = font.fontHeight + 4
        val left = HUD.position != "Right"
        val margin = HUD.posX
        // Right-aligned: value hugs the right edge, name sits to its left.
        val rightEdge = ctx.scaledWidth - margin
        // "Breathing": slow 3.2s pulse (matches the reference's bone-breathe).
        val breathe = ((System.currentTimeMillis() % 3200L) / 3200f).let {
            if (it < 0.5f) it * 2f else 2f - it * 2f   // 0..1..0 triangle wave
        }
        val breathOffset = (breathe - 0.5f) * -2f   // -1..1, for y lift

        // ── Left spine (gradient warm-orange vertical bar with glow) ──
        val spineX = if (left) margin + 2f else rightEdge - 6f
        val spineTop = (HUD.posY - 6).toFloat()
        val spineH = (entries.size * lineHeight + 12).toFloat()
        // Glow behind the spine
        RenderUtils.rect(ctx, spineX - 2, spineTop, 8f, spineH, RenderUtils.withAlpha(0xFFFFB432.toInt(), 0.10f + 0.12f * breathe))
        // The spine bar itself
        RenderUtils.rect(ctx, spineX, spineTop, 2f, spineH, RenderUtils.withAlpha(0xFFFFB432.toInt(), 0.55f + 0.30f * breathe))

        var y = HUD.posY
        for (entry in entries) {
            val isTitle = entry.name == "SwitchLite"
            val nameColor = when {
                isTitle -> Theme.ACCENT
                entry.isRed -> Theme.ERROR
                else -> Theme.TEXT
            }
            val lift = if (entry.isRed) breathOffset * 0.8f else breathOffset * 0.5f
            val rowY = (y + lift).toInt()

            // Bone row: translucent dark rounded card + border.
            val rowTextW = font.getStringWidth(entry.name) +
                (if (entry.value.isNotEmpty()) font.getStringWidth(entry.value) + 10 else 0)
            val rowW = (rowTextW + 22).toFloat()
            val rowH = (lineHeight + 2).toFloat()
            val rowX = if (left) margin.toFloat() else (rightEdge - rowW).toFloat()

            // Row background (bone) — red-tinted when enabled.
            val rowBg = if (entry.isRed) 0x331E0A0A.toInt() else RenderUtils.withAlpha(0x14120F16.toInt(), 0.22f + 0.06f * breathe)
            RenderUtils.roundedRect(ctx, rowX, rowY.toFloat(), rowW, rowH, 10f, rowBg)
            val border = if (entry.isRed)
                RenderUtils.withAlpha(0xFFFF4D4D.toInt(), 0.08f + 0.06f * breathe)
            else
                RenderUtils.withAlpha(0xFFFFFFFF.toInt(), 0.04f + 0.03f * breathe)
            RenderUtils.roundedRectOutline(ctx, rowX, rowY.toFloat(), rowW, rowH, 10f, border, 1f, rowBg)

            // Text
            var tx = (rowX + 10).toInt()
            font.drawStringWithShadow(entry.name, tx, rowY, nameColor)
            tx += font.getStringWidth(entry.name)
            if (entry.value.isNotEmpty()) {
                tx += 10
                val vc = if (entry.highlight) RenderUtils.withAlpha(Theme.ACCENT, 0.7f + 0.3f * breathe) else Theme.TEXT_DIM
                font.drawStringWithShadow(entry.value, tx, rowY, vc)
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
