package io.doppel.adapter.common.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.render.HUD
import io.doppel.adapter.common.ui.RenderUtils
import io.doppel.adapter.common.ui.Theme

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
                io.doppel.core.logging.CoreLogger.info("[Overlay.render] entered. HUD.enabled=${HUD.enabled}, entries=${HUD.hudEntries.size}")
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
            drawJumpStatus(ctx)
            drawJumpTiming(ctx)
            drawKnockbackDisplay(ctx)
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

    // ── HUD card (v3 — concept-faithful) ──
    //
    // Layout mirrors the approved HTML concept (download/doppel-hud-concept.html):
    //   [ᗡD DOPPEL]        ← mirrored-D mark + tracked wordmark, twin P in cyan
    //   ───────────────    ← cyan→violet hairline
    //   statistically you.  ← mono slogan
    //   AutoClicker  ▏      ← arraylist, width-sorted staircase (widest top),
    //   KeepSprint    ▏     ← right-edge spine bars lerped cyan→violet,
    //   ...                 ← rows flash red while their module is working
    //   [● DOPPEL | 8 ON]   ← glass badge, bottom-right, pulsing dot + live count

    private const val SLOGAN = "statistically you."
    private const val WORDMARK = "DOPPEL"
    private const val ENTRANCE_MS = 350L
    private const val ENTRANCE_SLIDE = 10f
    private const val STAGGER_MS = 45L

    /** Row entrance timestamps by module name (new rows slide in staggered). */
    private val rowAppear = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Badge glass texture (downsample-blur capture target). */
    private var badgeTexId: Int = -1
    private var badgeTexAllocated = false
    private var badgeBlurDisabled = false

    private fun drawHudCard(ctx: RenderContext) {
        if (!HUD.enabled) {
            if (!hudDiagLogged) { io.doppel.core.logging.CoreLogger.warn("[Overlay] drawHudCard: HUD disabled"); hudDiagLogged = true }
            return
        }
        val all = HUD.hudEntries
        if (all.isEmpty()) {
            if (!hudDiagLogged) { io.doppel.core.logging.CoreLogger.warn("[Overlay] drawHudCard: no entries (HUD enabled=${HUD.enabled})"); hudDiagLogged = true }
            return
        }
        if (!hudDiagLogged) {
            io.doppel.core.logging.CoreLogger.info("[Overlay] drawHudCard v3: ${all.size} entries, fonts=${if (ctx.hudFonts != null) "smooth" else "vanilla"}")
            hudDiagLogged = true
        }

        val fonts = ctx.hudFonts
        val font = fonts?.ui ?: ctx.fontRenderer
        val brandFont = fonts?.brand ?: ctx.fontRenderer
        val sloganFont = fonts?.slogan ?: ctx.fontRenderer
        val monoFont = fonts?.mono ?: ctx.fontRenderer
        val mirroredMark = fonts?.brand as? SmoothFontRenderer

        val now = System.currentTimeMillis()
        val margin = HUD.posX.coerceAtLeast(2)
        val right = HUD.position != "Left"

        val rows = all.filter { it.name != "Doppel" }
        pruneAppear(rows)
        val sorted = sortRows(font, rows)
        val n = sorted.size

        // ── metrics ──
        val barW = 2f
        val textBarGap = 4f
        val rowH = (font.fontHeight + 5).toFloat()
        val rowStep = rowH + 2f
        val widths = FloatArray(n) { font.getStringWidth(sorted[it].name).toFloat() }
        val listW = widths.maxOrNull() ?: 0f
        val rowW = listW + textBarGap + barW

        val track = 2f
        val markW = if (mirroredMark != null) brandFont.getStringWidth("D") + 4f else 0f
        val wordmarkW = measureTracked(brandFont, WORDMARK, track) + markW
        val sloganW = sloganFont.getStringWidth(SLOGAN).toFloat()
        val headerW = maxOf(rowW, wordmarkW, sloganW).coerceAtLeast(40f)

        val anchorX: Float = if (right) (ctx.scaledWidth - margin).toFloat() else margin.toFloat()
        val brandLineH = brandFont.fontHeight
        val hairY = HUD.posY + brandLineH + 2f
        val sloganY = hairY + 3f
        var y = sloganY + sloganFont.fontHeight + 5f

        // ── header ──
        var hx = if (right) anchorX - wordmarkW else anchorX
        if (mirroredMark != null) {
            val dW = brandFont.getStringWidth("D").toFloat()
            // Cyan mirrored ᗡ behind + white D in front (the doppelgänger mark).
            mirroredMark.drawCharMirrored('D', hx + dW * 0.45f, HUD.posY - 1f, Theme.ACCENT)
            brandFont.drawStringWithShadow("D", Math.round(hx), HUD.posY - 1, Theme.TEXT)
            hx += dW + 4f
        }
        drawTracked(brandFont, WORDMARK, hx, (HUD.posY - 1).toFloat(), track) { i, _ ->
            if (i == 2 || i == 3) Theme.ACCENT else Theme.TEXT
        }
        val hairX = if (right) anchorX - headerW else anchorX
        RenderUtils.horizontalGradient(
            ctx, hairX, hairY, headerW, 1f,
            Theme.withAlpha(Theme.ACCENT, 0.70f), 0x00A78BFA
        )
        val sloganX = if (right) anchorX - sloganW else anchorX
        sloganFont.drawStringWithShadow(SLOGAN, Math.round(sloganX), Math.round(sloganY), Theme.TEXT_FAINT)

        // ── module rows ──
        for (i in 0 until n) {
            val entry = sorted[i]
            val w = widths[i]
            val ease = entranceEase(entry.name, i, now)
            if (ease <= 0f) continue
            val alphaMul = 0.25f + 0.75f * ease
            val slide = (1f - ease) * ENTRANCE_SLIDE

            val module = io.doppel.adapter.common.module.ModuleRegistry.get(entry.name)
            val k = module?.flashStrength(now) ?: 0f

            val barX = if (right) anchorX - slide - barW else anchorX + w + textBarGap + slide
            val textX = if (right) barX - textBarGap - w else barX + barW + textBarGap
            val baseText = Theme.withAlpha(Theme.TEXT, 0.92f * alphaMul)
            val textCol = if (k > 0f) Theme.lerpArgb(baseText, Theme.FLASH_RED_TEXT, k) else baseText
            val baseBar = Theme.withAlpha(Theme.spineColor(i, n), alphaMul)
            val barCol = if (k > 0f) Theme.lerpArgb(baseBar, Theme.FLASH_RED, k) else baseBar

            if (k > 0f) {
                // Work flash: soft red halo + row tint (successor of the isRed bar).
                RenderUtils.glow(ctx, barX - w - textBarGap, y, w + textBarGap + barW, rowH, 3f, Theme.FLASH_RED, spread = 2.5f, layers = 2)
                RenderUtils.roundedRect(ctx, barX - w - textBarGap - 2f, y - 1f, w + textBarGap + barW + 4f, rowH + 2f, 3f, Theme.withAlpha(0x66FF5A5A.toInt(), k * 0.16f))
            }

            font.drawStringWithShadow(entry.name, Math.round(textX), Math.round(y), textCol)
            RenderUtils.roundedRect(ctx, barX, y + 1f, barW, rowH - 4f, 1f, barCol)
            y += rowStep
        }

        // ── badge (bottom-right enable display) ──
        drawBadge(ctx, font, monoFont, n, margin, now)
    }

    /** Glass panel + pulsing accent dot + "DOPPEL | N ON". Always bottom-right. */
    private fun drawBadge(
        ctx: RenderContext,
        font: io.doppel.adapter.common.render.FontRendererBridge,
        monoFont: io.doppel.adapter.common.render.FontRendererBridge,
        count: Int,
        margin: Int,
        now: Long
    ) {
        val dotR = 1.6f
        val padX = 5f
        val labelTrack = 1f
        val labelW = measureTracked(font, WORDMARK, labelTrack)
        val countText = "$count ON"
        val countW = monoFont.getStringWidth(countText)
        val badgeW = padX + dotR * 2 + 4f + labelW + 5f + 1f + 5f + countW + padX
        val badgeH = font.fontHeight + 6f
        val bx = ctx.scaledWidth - margin - badgeW
        val by = ctx.scaledHeight - margin - badgeH

        drawGlass(ctx, bx, by, badgeW, badgeH, 4f)

        // Pulsing dot: solid core + expanding fading ring (2.4s cycle).
        val cx = bx + padX + dotR
        val cy = by + badgeH / 2f
        val pulse = (now % 2400L) / 2400f
        RenderUtils.circle(ctx, cx, cy, dotR + pulse * 2.6f, Theme.withAlpha(Theme.ACCENT, (1f - pulse) * 0.45f))
        RenderUtils.circle(ctx, cx, cy, dotR, Theme.ACCENT)

        val textY = by + (badgeH - font.fontHeight) / 2f
        drawTracked(font, WORDMARK, cx + dotR + 4f, textY, labelTrack) { _, _ -> Theme.TEXT_FAINT }
        val sepX = bx + padX + dotR * 2 + 4f + labelW + 5f
        RenderUtils.rect(ctx, sepX, by + 3f, 1f, badgeH - 6f, 0x24FFFFFF)
        val digitsX = sepX + 5f
        val cnt = count.toString()
        monoFont.drawStringWithShadow(cnt, Math.round(digitsX), Math.round(textY), Theme.ACCENT)
        monoFont.drawStringWithShadow(" ON", Math.round(digitsX + monoFont.getStringWidth(cnt)), Math.round(textY), Theme.TEXT_FAINT)
    }

    /**
     * Frosted-glass panel: capture the framebuffer region behind the rect into
     * a small texture, redraw it upscaled (cheap linear blur), then lay a dark
     * translucent rounded panel + accent hairline border on top. Falls back to
     * the flat panel alone when GL copy is unavailable.
     */
    private fun drawGlass(ctx: RenderContext, x: Float, y: Float, w: Float, h: Float, r: Float) {
        var blurred = false
        if (!badgeBlurDisabled) {
            try {
                val gl = ctx.gl
                if (badgeTexId == -1) badgeTexId = gl.glGenTextures()
                if (badgeTexId > 0) {
                    if (!badgeTexAllocated) {
                        // Allocate once (zeroed) so glCopyTexSubImage2D has storage.
                        gl.glBindTexture(badgeTexId)
                        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MIN_FILTER, GLConstants.GL_LINEAR)
                        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_MAG_FILTER, GLConstants.GL_LINEAR)
                        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_WRAP_S, GLConstants.GL_CLAMP_TO_EDGE)
                        gl.glTexParameteri(GLConstants.GL_TEXTURE_2D, GLConstants.GL_TEXTURE_WRAP_T, GLConstants.GL_CLAMP_TO_EDGE)
                        gl.glTexImage2DRGBA(512, 128, java.nio.ByteBuffer.allocateDirect(512 * 128 * 4))
                        badgeTexAllocated = true
                    }
                    val gs = ctx.guiScale.coerceAtLeast(1)
                    val pw = (w * gs).toInt().coerceIn(2, 512)
                    val ph = (h * gs).toInt().coerceIn(2, 128)
                    val glX = (x * gs).toInt()
                    val glY = ((ctx.scaledHeight - y - h) * gs).toInt()
                    gl.glBindTexture(badgeTexId)
                    if (gl.glCopyTexSubImage2D(glX, glY, pw, ph)) {
                        gl.glEnable(GLConstants.GL_TEXTURE_2D)
                        gl.glEnable(GLConstants.GL_BLEND)
                        gl.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
                        gl.glDisable(GLConstants.GL_DEPTH_TEST)
                        gl.glDepthMask(false)
                        gl.glDisable(GLConstants.GL_ALPHA_TEST)
                        gl.glTexEnv(GLConstants.GL_TEXTURE_ENV, GLConstants.GL_TEXTURE_ENV_MODE, GLConstants.GL_MODULATE)
                        gl.glColor4f(0.30f, 0.36f, 0.47f, 0.40f) // cool glass tint over the captured scene
                        gl.glBegin(GLConstants.GL_QUADS)
                        gl.glTexCoord2f(0f, 0f); gl.glVertex2f(x, y)
                        gl.glTexCoord2f(pw / 512f, 0f); gl.glVertex2f(x + w, y)
                        gl.glTexCoord2f(pw / 512f, ph / 128f); gl.glVertex2f(x + w, y + h)
                        gl.glTexCoord2f(0f, ph / 128f); gl.glVertex2f(x, y + h)
                        gl.glEnd()
                        blurred = true
                    }
                }
            } catch (_: Exception) {
                badgeBlurDisabled = true
            }
        }
        val base = Theme.withAlpha(0x0D111C.toInt(), if (blurred) 0.42f else 0.66f)
        RenderUtils.roundedRect(ctx, x, y, w, h, r, base)
        RenderUtils.roundedRectOutline(ctx, x, y, w, h, r, Theme.withAlpha(Theme.ACCENT, 0.18f), 1f, base)
    }

    /** Keep only current module names; new names get staggered entrance slots. */
    private fun pruneAppear(rows: List<HUD.HUDEntry>) {
        val names = rows.map { it.name }.toHashSet()
        rowAppear.keys.retainAll(names)
        rows.forEach { rowAppear.computeIfAbsent(it.name) { Long.MAX_VALUE } } // placeholder; index set below
    }

    /** 0→1 ease-out-cubic entrance progress for the row (staggered by list index). */
    private fun entranceEase(name: String, index: Int, now: Long): Float {
        var ts = rowAppear[name]
        if (ts == null || ts == Long.MAX_VALUE) {
            ts = now + index * STAGGER_MS
            rowAppear[name] = ts
        }
        val t = ((now - ts).toFloat() / ENTRANCE_MS).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** Default sort = width-descending staircase; user Sort/Reversed still honored. */
    private fun sortRows(font: io.doppel.adapter.common.render.FontRendererBridge, rows: List<HUD.HUDEntry>): List<HUD.HUDEntry> {
        val base = when (HUD.sortMode) {
            "Alphabetical" -> compareBy<HUD.HUDEntry> { it.name }
            "Length" -> compareBy<HUD.HUDEntry> { it.name.length }
            else -> compareByDescending<HUD.HUDEntry> { font.getStringWidth(it.name) }
        }
        return if (HUD.reversed) rows.sortedWith(base.reversed()) else rows.sortedWith(base)
    }

    private fun measureTracked(font: io.doppel.adapter.common.render.FontRendererBridge, text: String, track: Float): Float {
        if (text.isEmpty()) return 0f
        var w = 0f
        for (ch in text) w += font.getStringWidth(ch.toString()) + track
        return w - track
    }

    private fun drawTracked(
        font: io.doppel.adapter.common.render.FontRendererBridge,
        text: String, x: Float, y: Float, track: Float,
        color: (Int, Char) -> Int
    ): Float {
        var cx = x
        for ((i, ch) in text.withIndex()) {
            font.drawStringWithShadow(ch.toString(), Math.round(cx), Math.round(y), color(i, ch))
            cx += font.getStringWidth(ch.toString()) + track
        }
        return cx - track
    }

    // ── Keystrokes (in-game key press indicator) ──

    private fun drawKeystrokes(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.Keystrokes.render(ctx)
        } catch (_: Exception) {}
    }

    // ── Speedometer (plain-number speed HUD) ──

    private fun drawSpeedometer(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.Speedometer.render(ctx)
        } catch (_: Exception) {}
    }

    // ── VelocityDisplay (3D motion readout, colors when velocity was modified) ──

    private fun drawVelocityDisplay(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.VelocityDisplay.render(ctx)
        } catch (_: Exception) {}
    }

    // ── JumpStatus (jump key state readout) ──

    private fun drawJumpStatus(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.JumpStatus.render(ctx)
        } catch (_: Exception) {}
    }

    // ── JumpTiming (jump-reset timing window + manual success rate) ──

    private fun drawJumpTiming(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.JumpTiming.render(ctx)
        } catch (_: Exception) {}
    }

    // ── KnockbackDisplay (retain/cut % + knockback distance) ──

    private fun drawKnockbackDisplay(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.render.KnockbackDisplay.render(ctx)
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
