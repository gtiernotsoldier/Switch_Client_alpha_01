package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.adapter.common.option.choices
import io.doppel.adapter.common.option.color
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.render.RenderContext
import io.doppel.adapter.common.ui.RenderUtils

/**
 * Keystrokes — in-game key/button press indicator, a Kotlin port of **Lunar Keystrokes Mod v3-3.0**
 * (`com.rakib.keystrokes`).
 *
 * ## Layout (Lunar, unscaled units — see [KeystrokesLayout] for the bytecode evidence)
 * ```
 *   WASD mode                        +Mouse mode                     +Jump mode
 *   ┌────┬────┬────┐                 ┌────┬────┬────┐                ┌────┬────┬────┐
 *   │    │ W  │    │   18x18         │    │ W  │    │                │    │ W  │    │
 *   ├────┼────┼────┤                 ├────┼────┼────┤                ├────┼────┼────┤
 *   │ A  │ S  │ D  │                 │ A  │ S  │ D  │                │ A  │ S  │ D  │
 *   └────┴────┴────┘                 ├──────┬──────┤                ├──────┬──────┤
 *                                    │ LMB  │ RMB  │  28x18         │ LMB  │ RMB  │
 *                                    └──────┴──────┘                ├─────────────┤
 *                                                                   │    Jump     │ 58x12
 *                                                                   └─────────────┘
 * W(21,1) A(1,21) S(21,21) D(41,21)  LMB(1,41) RMB(31,41)  Jump(1,61,58,12)
 * widget = max(x+w)+4 x max(y+h)+4   →  63x43 | 63x63 | 63x77
 * ```
 * Boxes are flat unrounded rectangles (no outline, no shadow): released `0x55000000`,
 * pressed `0xCC000000`. The label is centred with shadow at `x + w/2 - textWidth/2, y + h/2 - 4`
 * and is tinted `keystrokesDefaultColor` (`0xFFFFFFFF`) or `keystrokesPressedColor` (`0xFFAAAAAA`).
 * The LMB/RMB label is replaced by the raw live CPS count whenever that count is non-zero (the
 * reference draws no separate "CPS" text and no sprint block).
 *
 * ## Options (reference config `config/lunar-keystrokes.cfg` / `Config.<clinit>`)
 * - `Mode`         — `keystrokesMode`: WASD | WASD+Mouse | WASD+Jump+Mouse
 * - `Scale`        — `keystrokesScale`, 1.0 default, clamped 0.5..3.0 (as the reference GUI does)
 * - `DefaultColor` — `keystrokesDefaultColor` = `0xFFFFFFFF`
 * - `PressedColor` — `keystrokesPressedColor` = `0xFFAAAAAA`
 * - `ShowCPS`      — the reference always shows it; this toggle keeps that as the default
 * - position       — `keystrokesX`/`keystrokesY`, defaulted to the shipped `(4, 4)` and moved by
 *                    dragging the widget while a GUI screen is open (this module's existing
 *                    behaviour; the values live for the session, as before)
 *
 * Drawn through the shared [RenderUtils] primitives and the vanilla/`RenderContext` font — the
 * whole widget is pushed through `glTranslatef(posX, posY) + glScalef(scale, scale, 1)` exactly
 * like the reference, so the text scales with the boxes.
 */
object Keystrokes : Module("Keystrokes", Category.RENDER) {

    @Volatile
    var posX: Int = KeystrokesLayout.DEFAULT_X
        private set
    @Volatile
    var posY: Int = KeystrokesLayout.DEFAULT_Y
        private set

    /** `keystrokesMode` — WASD / WASD+Mouse / WASD+Jump+Mouse (reference default: WASD). */
    var mode by choices("Mode", KeystrokeMode.LABELS)

    /** `keystrokesScale` — reference HUD scaling factor, 0.5..3.0. */
    var scale by float("Scale", KeystrokesLayout.DEFAULT_SCALE, KeystrokesLayout.MIN_SCALE..KeystrokesLayout.MAX_SCALE)

    /** `keystrokesDefaultColor` (reference `0xFFFFFFFF`; stored as `0x00RRGGBB`). */
    var defaultColor by color("DefaultColor", KeystrokesLayout.TEXT_DEFAULT_RGB)

    /** `keystrokesPressedColor` (reference `0xFFAAAAAA`; stored as `0x00RRGGBB`). */
    var pressedColor by color("PressedColor", KeystrokesLayout.TEXT_PRESSED_RGB)

    /** The reference always substitutes the LMB/RMB label with the CPS count; `true` = that look. */
    var showCps by boolean("ShowCPS", true)

    // ── Drag state ──

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    fun render(ctx: RenderContext) {
        if (!enabled) return
        var layout = snapshot(ctx)
        if (EventBridge.isGuiOpen) layout = handleDrag(ctx, layout)
        // Keep the stored origin inside the screen (the previous clampToScreen behaviour) so the
        // drawn widget and the drag hit box always agree, including after a scale change.
        posX = layout.originX
        posY = layout.originY
        draw(ctx, layout)
    }

    // ═══════════════════════════════════════════
    //  Layout snapshot (pure — see KeystrokesLayout)
    // ═══════════════════════════════════════════

    private fun snapshot(ctx: RenderContext): KeystrokesLayout.Layout = KeystrokesLayout.compute(
        keyStates = KeystrokesLayout.KeyStates(
            forward = EventBridge.isKeyForwardDown,
            back = EventBridge.isKeyBackDown,
            left = EventBridge.isKeyLeftDown,
            right = EventBridge.isKeyRightDown,
            jump = EventBridge.isKeyJumpDown,
            mouseLeft = EventBridge.mouseButton0,
            mouseRight = EventBridge.mouseButton1
        ),
        leftCps = EventBridge.leftCps(),
        rightCps = EventBridge.rightCps(),
        options = KeystrokesLayout.Options(
            mode = KeystrokeMode.forLabel(mode),
            scale = scale,
            defaultColor = defaultColor,
            pressedColor = pressedColor,
            showCps = showCps
        ),
        originX = posX,
        originY = posY,
        screenWidth = ctx.scaledWidth,
        screenHeight = ctx.scaledHeight
    )

    // ═══════════════════════════════════════════
    //  Drag (only while a GUI screen is open / paused)
    // ═══════════════════════════════════════════

    private fun handleDrag(ctx: RenderContext, start: KeystrokesLayout.Layout): KeystrokesLayout.Layout {
        if (!EventBridge.isGuiOpen) {
            dragging = false
            return start
        }
        if (!EventBridge.guiLeftMouseDown) {
            dragging = false
            return start
        }
        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY

        if (!dragging) {
            if (mx >= start.originX && mx < start.originX + start.screenWidth &&
                my >= start.originY && my < start.originY + start.screenHeight
            ) {
                dragging = true
                dragOffsetX = mx - start.originX
                dragOffsetY = my - start.originY
            }
            return start
        }

        posX = mx - dragOffsetX
        posY = my - dragOffsetY
        // Recompute so the widget is clamped to the screen and drawn where it was hit-tested.
        return snapshot(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drawing
    // ═══════════════════════════════════════════

    private fun draw(ctx: RenderContext, layout: KeystrokesLayout.Layout) {
        val g = ctx.gl
        val font = ctx.fontRenderer

        // Reference: glTranslatef(keystrokesX, keystrokesY, 0); glScalef(scale, scale, 1).
        // Inside this matrix every box is drawn in the unscaled Lunar coordinates.
        g.glPushMatrix()
        try {
            g.glTranslatef(layout.originX.toFloat(), layout.originY.toFloat(), 0f)
            g.glScalef(layout.scale, layout.scale, 1f)

            for (box in layout.boxes) {
                RenderUtils.rect(
                    ctx,
                    box.localX.toFloat(), box.localY.toFloat(),
                    box.localWidth.toFloat(), box.localHeight.toFloat(),
                    box.background
                )
                val textWidth = font.getStringWidth(box.label)
                font.drawStringWithShadow(box.label, box.textX(textWidth), box.textY, box.textColor)
            }
        } finally {
            g.glPopMatrix()
        }
    }
}
