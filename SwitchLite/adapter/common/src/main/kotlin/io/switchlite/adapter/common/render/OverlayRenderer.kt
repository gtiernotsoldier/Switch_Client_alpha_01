package io.switchlite.adapter.common.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.render.ClickGUI
import io.switchlite.adapter.common.module.render.HUD
import io.switchlite.adapter.common.ui.RenderUtils
import io.switchlite.adapter.common.ui.Theme

/**
 * Shared overlay rendering logic — version-agnostic.
 *
 * Draws the HUD card, ClickGUI panels and toast notifications using only
 * [GL11Bridge] and [FontRendererBridge] from the [RenderContext].
 * No Minecraft classes, no MappingContext, no reflection — pure rendering.
 *
 * Geometry / hit-testing lives in [ClickGUI] and [HUD] (pure data);
 * this object only draws what they lay out.
 */
object OverlayRenderer {

    private const val CORNER_RADIUS = 3.2f

    fun render(ctx: RenderContext) {
        val g = ctx.gl

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

            g.glDisable(GLConstants.GL_DEPTH_TEST)
            g.glDisable(GLConstants.GL_LIGHTING)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)

            // Explicit GL preconditions for the vanilla FontRenderer.
            // MC's FontRenderer assumes TEXTURE_2D enabled + BLEND active.
            g.glEnable(GLConstants.GL_TEXTURE_2D)
            g.glEnable(GLConstants.GL_BLEND)
            g.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
            g.glDisable(GLConstants.GL_DEPTH_TEST)

            drawHudCard(ctx)
            if (EventBridge.isGuiOpen) {
                drawClickGUI(ctx)
            }
            drawToasts(ctx)

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

    // ══════════════════════════════════════
    //  HUD card
    // ══════════════════════════════════════

    private fun drawHudCard(ctx: RenderContext) {
        if (!HUD.enabled) return

        val entries = HUD.sortedEntries()
        val lineHeight = font.fontHeight + 2
        val pad = 4
        val barW = 3

        // Measure card
        var maxTextW = font.getStringWidth("SwitchLite")
        for (e in entries) {
            maxTextW = maxOf(maxTextW, font.getStringWidth(e.name))
        }
        val cardW = pad * 2 + barW + 4 + maxTextW
        val cardH = pad + lineHeight + entries.size * lineHeight + pad

        // Drag (GUI-open only) — must run before drawing so pos updates apply this frame.
        HUD.handleMouseInput(
            EventBridge.guiMouseX, EventBridge.guiMouseY, EventBridge.guiLeftMouseDown,
            ctx.scaledWidth, ctx.scaledHeight, cardW, cardH
        )

        val x = HUD.posX
        val y = HUD.posY
        val factor = HUD.brightnessFactor()

        // Card background
        RenderUtils.roundedRect(
            ctx, x.toFloat(), y.toFloat(), cardW.toFloat(), cardH.toFloat(),
            CORNER_RADIUS, Theme.withAlpha(Theme.HUD_BG, factor.coerceAtMost(1f))
        )

        // Title
        font.drawStringWithShadow(
            "SwitchLite", x + pad + barW + 4, y + pad,
            Theme.shade(Theme.TEXT, factor)
        )

        // Rows: [2px status bar] [module name]
        var ry = y + pad + lineHeight
        for ((idx, entry) in entries.withIndex()) {
            val color = Theme.shade(HUD.entryColor(idx, entry), factor)
            RenderUtils.rect(
                ctx, (x + pad).toFloat(), (ry + 1).toFloat(),
                barW.toFloat(), (lineHeight - 2).toFloat(), color
            )
            font.drawStringWithShadow(entry.name, x + pad + barW + 4, ry, color)
            ry += lineHeight
        }

        // GUI-open hint (below the card, not part of it)
        if (EventBridge.isGuiOpen) {
            font.drawStringWithShadow(
                "\u00A7a[GUI Open] \u00A77RShift to close",
                x, y + cardH + 2, 0x00FF00
            )
        }
    }

    // ══════════════════════════════════════
    //  ClickGUI panels
    // ══════════════════════════════════════

    private fun drawClickGUI(ctx: RenderContext) {
        val lineHeight = font.fontHeight + 3
        val clickGui = ClickGUI

        clickGui.tickAnimations(lineHeight)

        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY

        for ((cat, rows) in clickGui.categoriesWithRows(lineHeight)) {
            val p = clickGui.panel(cat)
            val clipBottom = clickGui.contentClipBottom(cat, lineHeight)
            val panelH = clickGui.panelHeight(cat, lineHeight)

            // Panel background (full height; collapse animation clips the rows)
            RenderUtils.roundedRect(
                ctx, (p.x - 6).toFloat(), (p.y - 6).toFloat(),
                (ClickGUI.PANEL_WIDTH + 12).toFloat(), (panelH + 6).toFloat(),
                CORNER_RADIUS, Theme.PANEL_BG
            )

            // Title bar + collapse arrow
            font.drawStringWithShadow("\u00A76${cat.name}", p.x, p.y, Theme.TITLE)
            font.drawStringWithShadow(
                if (p.expanded) "\u00A77\u25BC" else "\u00A77\u25B2",
                p.x + ClickGUI.PANEL_WIDTH - 10, p.y, Theme.TEXT_DIM
            )

            // Module rows (clipped to the animated content area)
            for (row in rows) {
                if (row.y + row.height > clipBottom) continue

                val hovered = mx >= row.x && mx < row.x + row.width &&
                    my >= row.y && my < row.y + row.height
                if (hovered) {
                    RenderUtils.roundedRect(
                        ctx, (row.x - 4).toFloat(), (row.y - 1).toFloat(),
                        (row.width + 8).toFloat(), (row.height + 2).toFloat(),
                        2f, Theme.HOVER
                    )
                }

                // Toggle dot (right side)
                val dotColor = if (row.module.enabled) Theme.ACCENT else Theme.TEXT_DIM
                RenderUtils.rect(
                    ctx, row.toggleDotX.toFloat(), row.toggleDotY.toFloat(),
                    ClickGUI.TOGGLE_DOT_SIZE.toFloat(), ClickGUI.TOGGLE_DOT_SIZE.toFloat(), dotColor
                )

                // Status bar + name
                val textColor = if (row.module.enabled) Theme.TEXT else Theme.TEXT_DIM
                RenderUtils.rect(ctx, (row.x - 4).toFloat(), (row.y + 1).toFloat(), 2f, (row.height - 2).toFloat(), textColor)
                font.drawStringWithShadow(row.module.name, row.x + 2, row.y, textColor)
            }

            // Expanded setting items (clipped too)
            for (row in rows) {
                if (!row.expanded) continue
                for ((idx, item) in row.settings.withIndex()) {
                    val iy = row.y + row.height + idx * lineHeight
                    if (iy + lineHeight > clipBottom) continue
                    drawSettingItem(ctx, item, row, iy, lineHeight)
                }
            }
        }
    }

    private fun drawSettingItem(
        ctx: RenderContext,
        item: ClickGUI.SettingItem,
        row: ClickGUI.ModuleRow,
        y: Int,
        lineHeight: Int
    ) {
        val indentX = row.x + 10

        when (item) {
            is ClickGUI.BoolItem -> {
                font.drawStringWithShadow(item.name, indentX, y, Theme.TEXT)
                val stateText = if (item.value) "ON" else "OFF"
                val stateColor = if (item.value) Theme.ACCENT else Theme.TEXT_DIM
                font.drawStringWithShadow(
                    stateText, row.x + row.width - 34, y, stateColor
                )
            }

            is ClickGUI.ChoiceItem, is ClickGUI.EnumItem -> {
                val name = item.name
                val value = (item as? ClickGUI.ChoiceItem)?.value ?: (item as ClickGUI.EnumItem).value
                font.drawStringWithShadow(name, indentX, y, Theme.TEXT)
                font.drawStringWithShadow(
                    "\u00A7e$value", row.x + row.width - 60, y, Theme.WARN
                )
            }

            is ClickGUI.FloatItem, is ClickGUI.IntItem -> {
                val name = item.name
                val valueText: String
                val ratio: Float
                if (item is ClickGUI.FloatItem) {
                    valueText = "${item.value}${if (item.unit.isEmpty()) "" else item.unit}"
                    ratio = ((item.value - item.min) / (item.max - item.min).coerceAtLeast(0.0001f))
                        .coerceIn(0f, 1f)
                } else {
                    val it = item as ClickGUI.IntItem
                    valueText = "${it.value}${if (it.unit.isEmpty()) "" else it.unit}"
                    ratio = ((it.value - it.min).toFloat() / (it.max - it.min).toFloat().coerceAtLeast(0.0001f))
                        .coerceIn(0f, 1f)
                }

                font.drawStringWithShadow(name, indentX, y, Theme.TEXT)

                val (trackStart, trackWidth) = ClickGUI.sliderTrack(row)
                val cy = y + lineHeight / 2.0f
                // Track
                RenderUtils.rect(ctx, trackStart, cy - 1f, trackWidth, 2f, Theme.TRACK)
                // Filled portion
                RenderUtils.rect(
                    ctx, trackStart, cy - 1f, trackWidth * ratio, 2f,
                    Theme.withAlpha(Theme.ACCENT, 0.6f)
                )
                // Knob
                RenderUtils.rect(
                    ctx, trackStart + trackWidth * ratio - 2f, cy - 4f, 4f, 8f, Theme.KNOB
                )
                // Value text
                font.drawStringWithShadow(
                    valueText, row.x + row.width - 34, y, Theme.TEXT
                )
            }
        }
    }

    // ══════════════════════════════════════
    //  Toasts
    // ══════════════════════════════════════

    private fun drawToasts(ctx: RenderContext) {
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
