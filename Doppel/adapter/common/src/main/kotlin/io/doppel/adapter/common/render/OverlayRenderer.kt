package io.doppel.adapter.common.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.render.HUD
import io.doppel.adapter.common.ui.RenderUtils
import io.doppel.adapter.common.ui.Theme
import io.doppel.contract.logging.CoreLogger

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
    private var rowDrawErrLogged = false

    fun render(ctx: RenderContext) {
        val g = ctx.gl

        // Texture bindings are NOT part of the attribute stack: save the
        // game's current binding so the HUD can hand it back EXACTLY when
        // done. Without this, any texture we leave bound desyncs MC's own
        // cached state — MC then skips its "redundant" rebind and samples
        // OUR texture (garbled/invisible game UI after a HUD frame).
        val prevTextureBinding = g.glGetInteger(GLConstants.GL_TEXTURE_BINDING_2D)

        // GL State: Save
        g.glPushAttrib(GLConstants.GL_ALL_ATTRIB_BITS)
        g.glMatrixMode(GLConstants.GL_PROJECTION)
        g.glPushMatrix()
        g.glMatrixMode(GLConstants.GL_MODELVIEW)
        g.glPushMatrix()

        try {
            if (!renderEntryDiagLogged) {
                renderEntryDiagLogged = true
                io.doppel.contract.logging.CoreLogger.info("[Overlay.render] entered. HUD.enabled=${HUD.enabled}, entries=${HUD.hudEntries.size}")
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

            drawKeystrokes(ctx)
        drawBridgeAssist(ctx)
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
            g.glBindTexture(prevTextureBinding)
            g.glPopAttrib()
        }
    }

    //
    //   AutoClicker ▏              ╭───────────────────╮
    //   KeepSprint  ▏              │ ᗡD DOPPEL (alpha) │ ← compact glass pill:
    //   WTap        ▏              ╰───────────────────╯   mirrored-D mark + tracked
    //   ...                                                 wordmark + version chip
    //   module list: width-sorted staircase, per-row spine bar
    //   lerped cyan→violet; rows flash red while their module works
    //                              [● DOPPEL | 8 ON]  ← glass badge, bottom-right
    //
    // HUD.position picks the LIST side ("Left" default); the brand pill always
    // takes the opposite top corner.

    private const val VERSION_CHIP = "alpha"
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

    /** Consecutive glass copy failures → texture name likely dead, regenerate. */
    private var badgeCopyFails = 0

    // ── BridgeAssist (the bridging block count, Raven-bPLUS's SafeWalk readout) ──

    private fun drawBridgeAssist(ctx: RenderContext) {
        try {
            io.doppel.adapter.common.module.player.BridgeAssist.render(ctx)
        } catch (_: Exception) {}
    }

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
