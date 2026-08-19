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
            val entrance = clickGui.entrance(cat)
            // Aurora card entrance is a pure transform (slide+scale); the card is
            // always fully drawn at its target opacity — no alpha fade, ever.
            val slide = (1f - entrance) * 14f
            val scale = 0.95f + 0.05f * entrance

            val px = (p.x - 6).toFloat()
            val py = (p.y - 6).toFloat()
            val pw = (ClickGUI.PANEL_WIDTH + 12).toFloat()
            val ph = (panelH + 6).toFloat()
            val cx = p.x + ClickGUI.PANEL_WIDTH / 2f
            val cy = p.y + clickGui.titleBarHeight(lineHeight) / 2f + slide

            g.glPushMatrix()
            g.glTranslatef(cx, cy, 0f)
            g.glScalef(scale, scale, 1f)
            g.glTranslatef(-cx, -cy, 0f)

            // ── Aurora card (HTML .card): deep soft shadow, 85% translucent
            // dark fill, 1px 13% white border, subtle inner top highlight ──
            RenderUtils.shadow(ctx, px, py, pw, ph, PANEL_RADIUS, depth = 5, color = 0x8C000000.toInt())
            RenderUtils.roundedRect(ctx, px, py, pw, ph, PANEL_RADIUS, 0xD91A1A24.toInt())
            RenderUtils.roundedRectOutline(
                ctx, px, py, pw, ph, PANEL_RADIUS,
                0x21FFFFFF.toInt(), 1f, 0xD91A1A24.toInt()
            )
            RenderUtils.rect(
                ctx, px + 10, py + 2, pw - 20, 1f, 0x12FFFFFF.toInt()
            )

            // ── Title + count — SAME card background as the module list
            // (no separate header bar, per user: 标题跟列表一起、背景相同) ──
            val titleY = p.y + 7
            font.drawStringWithShadow(cat.name, p.x, titleY, accent)
            val countText = "${rows.size} mods"
            val countW = font.getStringWidth(countText) + 14
            val countX = p.x + ClickGUI.PANEL_WIDTH - countW - 14
            RenderUtils.roundedRect(
                ctx, countX.toFloat(), (p.y + 3).toFloat(),
                countW.toFloat(), (font.fontHeight + 5).toFloat(),
                (font.fontHeight + 5) / 2f, 0x12FFFFFF.toInt()
            )
            RenderUtils.roundedRectOutline(
                ctx, countX.toFloat(), (p.y + 3).toFloat(),
                countW.toFloat(), (font.fontHeight + 5).toFloat(),
                (font.fontHeight + 5) / 2f, 0x1AFFFFFF.toInt(), 1f, 0x12FFFFFF.toInt()
            )
            font.drawStringWithShadow(
                countText, countX + 7, p.y + 5, Theme.TEXT_FAINT
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
                val on = row.module.enabled

                // HTML .row: rgba(255,255,255,.045) fill + 6% border, 10px radius.
                // Hover: .065 fill + lift + shadow. Expanded/on: accent border.
                if (hovered) {
                    RenderUtils.shadow(ctx, rx, ry + lift, rw, rh, 9f, depth = 2, color = 0x66000000.toInt())
                }
                RenderUtils.roundedRect(
                    ctx, rx, ry + lift, rw, rh, 10f,
                    if (hovered) 0x10FFFFFF.toInt() else 0x0BFFFFFF.toInt()
                )
                if (on) {
                    RenderUtils.roundedRectOutline(
                        ctx, rx, ry + lift, rw, rh, 10f,
                        Theme.accentSoft(cat), 1f,
                        if (hovered) 0x10FFFFFF.toInt() else 0x0BFFFFFF.toInt()
                    )
                } else {
                    RenderUtils.roundedRectOutline(
                        ctx, rx, ry + lift, rw, rh, 10f,
                        0x0FFFFFFF.toInt(), 1f,
                        if (hovered) 0x10FFFFFF.toInt() else 0x0BFFFFFF.toInt()
                    )
                }

                // Module name (HTML .r-name: 72% white, full white when on)
                val textColor = if (on) Theme.TEXT else 0xB7FFFFFF.toInt()
                val textY = (row.y + (row.height - font.fontHeight) / 2 + lift).toInt()
                font.drawStringWithShadow(row.module.name, row.x + 3, textY, textColor)

                // ── Aurora 60px capsule toggle (HTML .tgl) ──
                drawCapsuleToggle(ctx, row, accent, lift)

                // Hover "lift": a 2px accent bar at the row's left edge
                if (hovered) {
                    RenderUtils.rect(
                        ctx, rx, (row.y + 2 + lift).toFloat(),
                        2f, (row.height - 4).toFloat(), accent
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
                        RenderUtils.withAlpha(accent, 0.4f)
                    )
                }
                for ((idx, item) in row.settings.withIndex()) {
                    val iy = row.y + row.height + idx * lineHeight
                    if (iy + lineHeight > clipBottom) continue
                    drawSettingItem(ctx, item, row, iy, lineHeight, cat)
                }
            }

            g.glPopMatrix()
        }
    }

    /** Aurora 60px capsule toggle — HTML .tgl: 60×22, dark track, 14% white
     *  border, grey knob; ON = accent track + white knob + glow. Always visible. */
    private fun drawCapsuleToggle(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        accent: Int,
        lift: Float = 0f
    ) {
        val w = 60f
        val h = 22f
        // Right edge of the row, vertically centered in the row height.
        val x = (row.x + row.width - w - 4).toFloat()
        val y = row.y + (row.height - h.toInt()) / 2f + lift
        val on = row.module.enabled

        if (on) {
            RenderUtils.glow(ctx, x, y, w, h, 11f, accent, spread = 4f, layers = 3)
        }
        // Track: rgba(0,0,0,.32) fill + rgba(255,255,255,.14) border (visible off).
        RenderUtils.roundedRect(ctx, x, y, w, h, 11f, if (on) accent else 0x52000000.toInt())
        RenderUtils.roundedRectOutline(
            ctx, x, y, w, h, 11f,
            if (on) accent else 0x24FFFFFF.toInt(), 1f,
            if (on) accent else 0x52000000.toInt()
        )
        // Knob: 16px, 55% white off / pure white on, slides with the state.
        val knob = 16f
        val knobX = if (on) x + w - knob - 3f else x + 3f
        val knobY = y + (h - knob) / 2f
        RenderUtils.roundedRect(
            ctx, knobX, knobY, knob, knob, knob / 2f,
            if (on) Theme.TEXT else 0x8CFFFFFF.toInt()
        )
    }

    private fun drawSettingItem(
        ctx: RenderContext,
        item: ClickGUI.SettingItem,
        row: ClickGUI.ModuleRow,
        y: Int,
        lineHeight: Int,
        category: Category
    ) {
        val font = ctx.fontRenderer
        // Indent past the Aurora settings left-border line (row.x + 10)
        val indentX = row.x + 18
        val accent = Theme.accentFor(category)

        when (item) {
            is ClickGUI.BoolItem -> {
                font.drawStringWithShadow(item.name, indentX, y, Theme.TEXT_DIM)
                // mini pill toggle (HTML .pill: 38×18)
                drawMiniPill(ctx, row, y, item.value, accent)
            }

            is ClickGUI.ChoiceItem, is ClickGUI.EnumItem -> {
                val name = item.name
                val choices = (item as? ClickGUI.ChoiceItem)?.choices ?: (item as ClickGUI.EnumItem).choices
                val value = (item as? ClickGUI.ChoiceItem)?.value ?: (item as ClickGUI.EnumItem).value
                font.drawStringWithShadow(name, indentX, y, Theme.TEXT_DIM)
                // chip selector (HTML .chip: segmented control, active = accent)
                drawChip(ctx, row, y, value, choices, accent)
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

                font.drawStringWithShadow(name, indentX, y, Theme.TEXT_DIM)

                // Aurora accent slider (HTML .track: 5px rail, 13px knob)
                val (trackStart, trackWidth) = ClickGUI.sliderTrack(row)
                val cy = y + lineHeight / 2.0f
                RenderUtils.roundedRect(ctx, trackStart, cy - 2.5f, trackWidth, 5f, 2.5f, 0x21FFFFFF.toInt())
                if (ratio > 0f) {
                    RenderUtils.roundedRect(ctx, trackStart, cy - 2.5f, trackWidth * ratio, 5f, 2.5f, accent)
                }
                val knobX = trackStart + trackWidth * ratio - 6.5f
                RenderUtils.glow(ctx, knobX, cy - 6.5f, 13f, 13f, 6.5f, accent, spread = 3f, layers = 3)
                RenderUtils.roundedRect(ctx, knobX, cy - 6.5f, 13f, 13f, 6.5f, Theme.TEXT)
                font.drawStringWithShadow(
                    valueText, row.x + row.width - 34, y, Theme.TEXT
                )
            }
        }
    }

    /** Aurora mini pill toggle (HTML .pill): 38×18, dark track, 12% white border,
     *  13px knob; ON = accent track + white knob + glow. Always visible. */
    private fun drawMiniPill(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        y: Int,
        on: Boolean,
        accent: Int
    ) {
        val w = 38f
        val h = 18f
        val x = row.x + row.width - 50f
        val cy = y + 2f
        if (on) {
            RenderUtils.glow(ctx, x, cy, w, h, 9f, accent, spread = 3f, layers = 3)
        }
        RenderUtils.roundedRect(
            ctx, x, cy, w, h, 9f,
            if (on) accent else 0x52000000.toInt()
        )
        RenderUtils.roundedRectOutline(
            ctx, x, cy, w, h, 9f,
            if (on) accent else 0x1FFFFFFF.toInt(), 1f,
            if (on) accent else 0x52000000.toInt()
        )
        val ksize = 13f
        val kx = if (on) x + w - ksize - 2f else x + 2f
        val ky = cy + (h - ksize) / 2f
        RenderUtils.roundedRect(ctx, kx, ky, ksize, ksize, ksize / 2f, Theme.TEXT)
    }

    /** Aurora chip selector (HTML .chip): segmented control showing every choice,
     *  active segment filled with the accent color + glow. */
    private fun drawChip(
        ctx: RenderContext,
        row: ClickGUI.ModuleRow,
        y: Int,
        value: String,
        choices: List<String>,
        accent: Int
    ) {
        val font = ctx.fontRenderer
        // Measure total segment width (each: text + 18 padding).
        val segW = choices.map { font.getStringWidth(it) + 18f }
        val totalW = segW.sum() + 4f
        val x = (row.x + row.width - totalW - 10).toFloat()
        val cy = y + 2f
        // Container: rgba(0,0,0,.3) fill + 10% white border, fully round.
        RenderUtils.roundedRect(ctx, x, cy, totalW, 15f, 7.5f, 0x4D000000.toInt())
        RenderUtils.roundedRectOutline(ctx, x, cy, totalW, 15f, 7.5f, 0x1AFFFFFF.toInt(), 1f, 0x4D000000.toInt())

        var sx = x + 2f
        for ((i, choice) in choices.withIndex()) {
            val active = choice == value
            val sw = segW[i]
            if (active) {
                RenderUtils.roundedRect(ctx, sx, cy + 1.5f, sw - 1f, 12f, 6f, accent)
            }
            font.drawStringWithShadow(
                choice,
                (sx + 9 - font.getStringWidth(choice) / 2).toInt(),
                (cy + 3).toInt(),
                if (active) Theme.TEXT else Theme.TEXT_FAINT
            )
            sx += sw
        }
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
