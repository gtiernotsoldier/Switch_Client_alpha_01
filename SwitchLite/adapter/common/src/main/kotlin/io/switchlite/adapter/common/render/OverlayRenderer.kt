package io.switchlite.adapter.common.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
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

    private const val CORNER_RADIUS = 8f
    private const val PANEL_RADIUS = 14f
    private const val HUD_TITLE_BAR = 26

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
                // Transparent backdrop — the world stays visible behind the
                // ClickGUI panels (you need to see enemies coming). The panels
                // themselves are translucent; no full-screen dark rect.
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
        val font = ctx.fontRenderer
        if (!HUD.enabled) return

        val entries = HUD.sortedEntries()
        val lineHeight = font.fontHeight + 3
        val pad = 8
        val barW = 3

        // Measure card — roomier Aurora HUD.
        var maxTextW = font.getStringWidth("SwitchLite")
        for (e in entries) {
            maxTextW = maxOf(maxTextW, font.getStringWidth(e.name))
        }
        val cardW = pad * 2 + barW + 6 + maxTextW + 12
        val cardH = HUD_TITLE_BAR + pad + entries.size * lineHeight + pad

        // Drag (GUI-open only) — must run before drawing so pos updates apply this frame.
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

        // GUI-open hint (below the card, not part of it)
        if (EventBridge.isGuiOpen) {
            font.drawStringWithShadow(
                "\u00A7a[GUI Open] \u00A77RShift to close",
                x, y + cardH + 2, 0xFF00FF00.toInt()
            )
        }
    }

    // ══════════════════════════════════════
    //  ClickGUI panels
    // ══════════════════════════════════════

    private fun drawClickGUI(ctx: RenderContext) {
        val font = ctx.fontRenderer
        val lineHeight = ClickGUI.ROW_HEIGHT
        val clickGui = ClickGUI
        val g = ctx.gl

        clickGui.tickAnimations(lineHeight)

        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY

        for ((cat, rows) in clickGui.categoriesWithRows(lineHeight)) {
            val p = clickGui.panel(cat)
            val clipBottom = clickGui.contentClipBottom(cat, lineHeight)
            val panelH = clickGui.panelHeight(cat, lineHeight)
            val accent = Theme.accentFor(cat)
            val glow = Theme.accentGlow(cat)
            val entrance = clickGui.entrance(cat)
            val alphaMul = entrance.coerceIn(0f, 1f)
            val slide = (1f - entrance) * 18f
            val scale = 0.92f + 0.08f * entrance

            val px = (p.x - 6).toFloat()
            val py = (p.y - 6).toFloat()
            val pw = (ClickGUI.PANEL_WIDTH + 12).toFloat()
            val ph = (panelH + 6).toFloat()
            val cx = p.x + ClickGUI.PANEL_WIDTH / 2f
            val cy = p.y + clickGui.titleBarHeight(lineHeight) / 2f + slide

            // Aurora card entrance: scale + slide + fade (whole panel transform).
            g.glPushMatrix()
            g.glTranslatef(cx, cy, 0f)
            g.glScalef(scale, scale, 1f)
            g.glTranslatef(-cx, -cy, 0f)

            // Soft drop shadow + accent glow.
            RenderUtils.shadow(ctx, px, py, pw, ph, PANEL_RADIUS, depth = 4)
            RenderUtils.glow(ctx, px, py, pw, ph, PANEL_RADIUS, glow, spread = 5f, layers = 3)

            // Panel body: subtle vertical gradient + translucent fill.
            RenderUtils.verticalGradient(
                ctx, px, py, pw, ph,
                RenderUtils.withAlpha(0xFF1A1A24.toInt(), alphaMul),
                RenderUtils.withAlpha(0xFF14141E.toInt(), alphaMul),
                bands = 8
            )
            // 1px border + inner top highlight.
            RenderUtils.roundedRectOutline(
                ctx, px, py, pw, ph, PANEL_RADIUS,
                RenderUtils.withAlpha(Theme.BORDER, alphaMul), 1f,
                RenderUtils.withAlpha(0xFF14141E.toInt(), alphaMul)
            )
            RenderUtils.rect(
                ctx, px + 4, py + 2, pw - 8, 1f, RenderUtils.withAlpha(Theme.TOP_HIGHLIGHT, alphaMul)
            )

            // ── Title bar: category name in accent, count badge, collapse arrow ──
            val titleY = p.y + 7
            font.drawStringWithShadow(cat.name, p.x, titleY, RenderUtils.withAlpha(accent, alphaMul))
            val countText = "${rows.size} mods"
            val countW = font.getStringWidth(countText) + 12
            val countX = p.x + ClickGUI.PANEL_WIDTH - countW - 20
            RenderUtils.roundedRect(
                ctx, countX.toFloat(), (p.y + 4).toFloat(),
                countW.toFloat(), (font.fontHeight + 4).toFloat(),
                10f, RenderUtils.withAlpha(Theme.withAlpha(Theme.TEXT, 0.07f), alphaMul)
            )
            font.drawStringWithShadow(
                countText, countX + 6, p.y + 6, RenderUtils.withAlpha(Theme.TEXT_FAINT, alphaMul)
            )
            font.drawStringWithShadow(
                if (p.expanded) "\u00A7f\u25BC" else "\u00A7f\u25B2",
                p.x + ClickGUI.PANEL_WIDTH - 16, titleY, RenderUtils.withAlpha(Theme.TEXT_DIM, alphaMul)
            )

            // ── Module rows ──
            for (row in rows) {
                if (row.y + row.height > clipBottom) continue

                val hovered = mx >= row.x && mx < row.x + row.width &&
                    my >= row.y && my < row.y + row.height

                val rx = (row.x - 5).toFloat()
                val ry = (row.y - 1).toFloat()
                val rw = (row.width + 10).toFloat()
                val rh = (row.height + 2).toFloat()
                val lift = if (hovered) -1f else 0f

                // Aurora row: rounded, subtle bg; hover lifts + shadow.
                if (hovered) {
                    RenderUtils.shadow(ctx, rx, ry + lift, rw, rh, 9f, depth = 2)
                }
                RenderUtils.roundedRect(
                    ctx, rx, ry + lift, rw, rh, 9f,
                    RenderUtils.withAlpha(
                        if (row.module.enabled) Theme.withAlpha(accent, 0.12f) else Theme.HOVER,
                        alphaMul
                    )
                )
                if (row.module.enabled) {
                    RenderUtils.roundedRectOutline(
                        ctx, rx, ry + lift, rw, rh, 9f,
                        RenderUtils.withAlpha(Theme.accentSoft(cat), alphaMul), 1f,
                        RenderUtils.withAlpha(Theme.withAlpha(accent, 0.12f), alphaMul)
                    )
                }

                // Module name (vertically centered in the taller Aurora row)
                val textColor = if (row.module.enabled) Theme.TEXT else Theme.TEXT_DIM
                val textY = (row.y + (row.height - font.fontHeight) / 2 + lift).toInt()
                font.drawStringWithShadow(row.module.name, row.x + 3, textY, textColor)

                // ── Aurora 60px capsule toggle ──
                drawCapsuleToggle(ctx, row, accent, alphaMul, lift)

                // Hover "lift": a 2px accent bar at the row's left edge
                if (hovered) {
                    RenderUtils.rect(
                        ctx, rx, (row.y + 2 + lift).toFloat(),
                        2f, (row.height - 4).toFloat(), RenderUtils.withAlpha(accent, alphaMul)
                    )
                }
            }

            // Expanded setting items (clipped too)
            for (row in rows) {
                if (!row.expanded) continue
                // Aurora settings: left accent border line + indented list
                val setStartY = row.y + row.height
                val setCount = row.settings.size
                if (setCount > 0 && setStartY + setCount * lineHeight <= clipBottom) {
                    RenderUtils.rect(
                        ctx, (row.x + 10).toFloat(), setStartY.toFloat() + 2,
                        1f, (setCount * lineHeight - 4).toFloat(),
                        RenderUtils.withAlpha(accent, 0.4f * alphaMul)
                    )
                }
                for ((idx, item) in row.settings.withIndex()) {
                    val iy = row.y + row.height + idx * lineHeight
                    if (iy + lineHeight > clipBottom) continue
                    drawSettingItem(ctx, item, row, iy, lineHeight, cat, alphaMul)
                }
            }

            g.glPopMatrix()
        }
    }

    /** Aurora 60px capsule toggle — filled with accent when on. */
    private fun drawCapsuleToggle(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        accent: Int,
        alphaMul: Float = 1f,
        lift: Float = 0f
    ) {
        val w = 56f
        val h = 20f
        // Right edge of the row, vertically centered in the row height.
        val x = (row.x + row.width - w - 4).toFloat()
        val y = row.y + (row.height - h.toInt()) / 2f + lift
        val on = row.module.enabled

        // track (with soft glow when on)
        if (on) {
            RenderUtils.glow(ctx, x, y, w, h, 10f, accent, spread = 4f, layers = 3)
        }
        RenderUtils.roundedRect(
            ctx, x, y, w, h, 10f,
            RenderUtils.withAlpha(if (on) accent else Theme.withAlpha(Theme.TEXT, 0.12f), alphaMul)
        )
        // knob
        val knobX = if (on) x + w - 18f else x + 2f
        RenderUtils.roundedRect(ctx, knobX, y + 2f, 16f, 16f, 8f, Theme.TEXT)
    }

    private fun drawSettingItem(
        ctx: RenderContext,
        item: ClickGUI.SettingItem,
        row: ClickGUI.ModuleRow,
        y: Int,
        lineHeight: Int,
        category: Category,
        alphaMul: Float = 1f
    ) {
        val font = ctx.fontRenderer
        // Indent past the Aurora settings left-border line (row.x + 10)
        val indentX = row.x + 18
        val accent = Theme.accentFor(category)

        when (item) {
            is ClickGUI.BoolItem -> {
                font.drawStringWithShadow(item.name, indentX, y, RenderUtils.withAlpha(Theme.TEXT_DIM, alphaMul))
                // mini pill toggle
                drawMiniPill(ctx, row, y, item.value, accent, alphaMul)
            }

            is ClickGUI.ChoiceItem, is ClickGUI.EnumItem -> {
                val name = item.name
                val value = (item as? ClickGUI.ChoiceItem)?.value ?: (item as ClickGUI.EnumItem).value
                font.drawStringWithShadow(name, indentX, y, RenderUtils.withAlpha(Theme.TEXT_DIM, alphaMul))
                // chip selector (single current value highlighted)
                drawChip(ctx, row, y, value, accent, alphaMul)
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

                font.drawStringWithShadow(name, indentX, y, RenderUtils.withAlpha(Theme.TEXT_DIM, alphaMul))

                val (trackStart, trackWidth) = ClickGUI.sliderTrack(row)
                val cy = y + lineHeight / 2.0f
                // Aurora accent slider with glowing knob
                RenderUtils.roundedRect(ctx, trackStart, cy - 2f, trackWidth, 3f, 1.5f, Theme.TRACK)
                if (ratio > 0f) {
                    RenderUtils.roundedRect(ctx, trackStart, cy - 2f, trackWidth * ratio, 3f, 1.5f, RenderUtils.withAlpha(accent, alphaMul))
                }
                val knobX = trackStart + trackWidth * ratio - 6f
                RenderUtils.glow(ctx, knobX, cy - 6f, 12f, 12f, 6f, accent, spread = 3f, layers = 3)
                RenderUtils.roundedRect(ctx, knobX, cy - 6f, 12f, 12f, 6f, Theme.TEXT)
                font.drawStringWithShadow(
                    valueText, row.x + row.width - 34, y, RenderUtils.withAlpha(Theme.TEXT, alphaMul)
                )
            }
        }
    }

    /** Aurora mini pill toggle for booleans. */
    private fun drawMiniPill(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        y: Int,
        on: Boolean,
        accent: Int,
        alphaMul: Float = 1f
    ) {
        val w = 42f
        val h = 14f
        val x = row.x + row.width - 52f
        val cy = y + 4f
        if (on) {
            RenderUtils.glow(ctx, x, cy, w, h, 7f, accent, spread = 3f, layers = 3)
        }
        RenderUtils.roundedRect(
            ctx, x, cy, w, h, 7f,
            RenderUtils.withAlpha(if (on) accent else Theme.withAlpha(Theme.TEXT, 0.12f), alphaMul)
        )
        val kx = if (on) x + w - 16f else x + 2f
        RenderUtils.roundedRect(ctx, kx, cy + 2f, 10f, 10f, 5f, Theme.TEXT)
    }

    /** Aurora chip showing the current choice value. */
    private fun drawChip(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        y: Int,
        value: String,
        accent: Int,
        alphaMul: Float = 1f
    ) {
        val font = ctx.fontRenderer
        val w = font.getStringWidth(value) + 16
        val x = (row.x + row.width - w - 14).toFloat()
        val cy = y + 2f
        RenderUtils.roundedRect(
            ctx, x, cy, w.toFloat(), 15f, 7.5f, RenderUtils.withAlpha(accent, alphaMul)
        )
        font.drawStringWithShadow(value, (x + 8f).toInt(), y + 3, RenderUtils.withAlpha(Theme.TEXT, alphaMul))
    }

    // ══════════════════════════════════════
    //  Toasts
    // ══════════════════════════════════════

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
