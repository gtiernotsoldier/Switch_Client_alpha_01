package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.core.model.VelocityContext
import io.doppel.core.util.Vec3
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.render.RenderContext
import kotlin.math.sqrt

/**
 * KnockbackDisplay — two independent knockback readouts (user's design):
 *
 *   IN   — knockback RECEIVED by the player: the original S12 motion vector (blocks/tick) that
 *          Velocity saw, plus the knockback distance (horizontal displacement until the player
 *          settles) and the Velocity retain/cut % (kept/cut horizontal speed). When Velocity
 *          modified the last packet, the IN line is drawn in the accent color.
 *   OUT  — knockback DEALT to others: an S12 velocity packet that arrives for the entity the
 *          player just attacked (hurtTime rising edge → attack correlation), plus the target's
 *          measured horizontal displacement.
 *
 * Each source shows 4 values: X, Y, Z (the knockback vector) and D (distance).
 *
 * A fifth line shows the ratio between the two horizontal impulses: OUT / IN(effective). Above
 * 1.0 means we deal more than we take, below 1.0 means the opposite. "Effective" means the IN
 * impulse after our own Velocity cut, so the number answers the question a player actually asks
 * ("what did I eat versus what did he eat"), not "what did the server's formula produce". The
 * OUT side is the raw S12 the server sent for the target — if the opponent also runs Velocity,
 * his real displacement is smaller than this, and the ratio is correspondingly optimistic.
 * The line reads "--" unless both halves are live, because a ratio between an IN from three
 * seconds ago and an OUT from now is not an exchange.
 *
 * Rendering: 4 lines, draggable while a GUI is open, plain text with shadow, no background box.
 */
object KnockbackDisplay : Module("KnockbackDisplay", Category.VISUAL) {

    private const val DEALT_MATCH_MS = 600L
    private const val MEASURE_MS = 1500L
    /** After the measurement, keep the readout for this long, then reset it to zero. */
    private const val DISPLAY_HOLD_MS = 500L

    @Volatile
    var posX: Int = -1
        private set
    @Volatile
    var posY: Int = -1
        private set

    var scale by float("Scale", 1.0f, 0.5f..2.0f)

    // Draggable state
    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0

    // ── IN (received) state ──
    @Volatile private var inKbNano = 0L
    @Volatile private var inMotionX = 0.0
    @Volatile private var inMotionY = 0.0
    @Volatile private var inMotionZ = 0.0
    @Volatile private var inMeasuring = false
    @Volatile private var inStartX = 0.0
    @Volatile private var inStartZ = 0.0
    @Volatile private var inDistance = 0.0
    @Volatile private var inLastKbNano = 0L

    // ── OUT (dealt) state ──
    @Volatile private var outKbNano = 0L
    @Volatile private var outEntityId = -1
    @Volatile private var outMotionX = 0.0
    @Volatile private var outMotionY = 0.0
    @Volatile private var outMotionZ = 0.0
    @Volatile private var outMeasuring = false
    @Volatile private var outStartX = 0.0
    @Volatile private var outStartZ = 0.0
    @Volatile private var outLastPosX = 0.0
    @Volatile private var outLastPosZ = 0.0
    @Volatile private var outDistance = 0.0
    @Volatile private var outLastKbNano = 0L

    // Attack correlation (set by the attack listener on the background tick thread)
    @Volatile private var lastAttackedId = -1
    @Volatile private var lastAttackNano = 0L

    // ========== IN: velocity notifier (Netty thread) — capture the raw knockback vector ==========
    private val velocityNotifier: (VelocityContext) -> Unit = { ctx ->
        if (enabled) {
            inMotionX = ctx.originalMotion.x
            inMotionY = ctx.originalMotion.y
            inMotionZ = ctx.originalMotion.z
            inKbNano = System.nanoTime()
        }
    }

    // ========== Attack listener (background 20Hz) — remember whom we just hit ==========
    private val attackListener: (TargetState?) -> Unit = { target ->
        if (enabled && target != null) {
            lastAttackedId = target.entityId
            lastAttackNano = System.nanoTime()
        }
    }

    // ========== OUT: entity velocity notifier (Netty thread) — S12 for our recent target ==========
    private val entityVelocityNotifier: (Int, Vec3) -> Unit = { entityId, motion ->
        if (enabled) {
            if (entityId == lastAttackedId && System.nanoTime() - lastAttackNano <= DEALT_MATCH_MS * 1_000_000L) {
                outMotionX = motion.x
                outMotionY = motion.y
                outMotionZ = motion.z
                outEntityId = entityId
                outKbNano = System.nanoTime()
            }
        }
    }

    // ========== Tick (background 20Hz) — displacement measurement for both sources ==========
    private val tickListener: (PlayerState, TargetState?) -> Unit = { p, _ ->
        if (enabled) {
            // ── IN: player displacement after the knockback ──
            if (inKbNano != inLastKbNano) {
                inLastKbNano = inKbNano
                inMeasuring = true
                inStartX = p.position.x
                inStartZ = p.position.z
                inDistance = 0.0
            }
            if (inMeasuring) {
                val dx = p.position.x - inStartX
                val dz = p.position.z - inStartZ
                inDistance = sqrt(dx * dx + dz * dz)
                val settled = kotlin.math.abs(p.motionX) < 0.001 && kotlin.math.abs(p.motionZ) < 0.001
                if (settled || System.nanoTime() - inKbNano > MEASURE_MS * 1_000_000L) {
                    inMeasuring = false
                }
            }

            // ── OUT: target entity displacement after the knockback ──
            if (outKbNano != outLastKbNano) {
                outLastKbNano = outKbNano
                val pos = EventBridge.getEntityPosition(outEntityId)
                if (pos != null) {
                    outMeasuring = true
                    outStartX = pos.x
                    outStartZ = pos.z
                    outLastPosX = pos.x
                    outLastPosZ = pos.z
                    outDistance = 0.0
                } else {
                    outMeasuring = false
                }
            }
            if (outMeasuring) {
                val pos = EventBridge.getEntityPosition(outEntityId)
                if (pos == null) {
                    outMeasuring = false // despawned / out of range
                } else {
                    val dx = pos.x - outStartX
                    val dz = pos.z - outStartZ
                    outDistance = sqrt(dx * dx + dz * dz)
                    // Settled when the entity barely moves between 20Hz samples (KB consumed).
                    val moved = kotlin.math.abs(pos.x - outLastPosX) + kotlin.math.abs(pos.z - outLastPosZ)
                    if (moved < 0.001 || System.nanoTime() - outKbNano > MEASURE_MS * 1_000_000L) {
                        outMeasuring = false
                    }
                    outLastPosX = pos.x
                    outLastPosZ = pos.z
                }
            }

            // ── Reset to zero after the display hold — stale readouts must not linger ──
            if (inKbNano != 0L && System.nanoTime() - inKbNano > DISPLAY_HOLD_MS * 1_000_000L) {
                inKbNano = 0L
                inMotionX = 0.0; inMotionY = 0.0; inMotionZ = 0.0
                inDistance = 0.0
                inMeasuring = false
            }
            if (outKbNano != 0L && System.nanoTime() - outKbNano > DISPLAY_HOLD_MS * 1_000_000L) {
                outKbNano = 0L
                outMotionX = 0.0; outMotionY = 0.0; outMotionZ = 0.0
                outDistance = 0.0
                outMeasuring = false
            }
        }
    }

    /** First-launch placement: slightly below screen center so widgets don't stack. */
    private const val CENTER_OFFSET = 60

    fun render(ctx: RenderContext) {
        if (!enabled) return
        if (posX < 0 || posY < 0) {
            posX = (ctx.scaledWidth - widgetWidth(ctx)) / 2
            posY = (ctx.scaledHeight - widgetHeight(ctx)) / 2 + CENTER_OFFSET
            clampToScreen(ctx) // the offset could push it off a short screen
        }
        handleDrag(ctx)
        draw(ctx)
    }

    private fun handleDrag(ctx: RenderContext) {
        if (!EventBridge.isGuiOpen) { dragging = false; return }
        val mx = EventBridge.guiMouseX
        val my = EventBridge.guiMouseY
        val leftDown = EventBridge.guiLeftMouseDown
        val w = widgetWidth(ctx)
        val h = widgetHeight(ctx)
        if (leftDown) {
            if (!dragging) {
                if (mx in posX until (posX + w) && my in posY until (posY + h)) {
                    dragging = true
                    dragOffsetX = mx - posX
                    dragOffsetY = my - posY
                }
            } else {
                posX = mx - dragOffsetX
                posY = my - dragOffsetY
                clampToScreen(ctx)
            }
        } else {
            dragging = false
        }
    }

    private fun clampToScreen(ctx: RenderContext) {
        if (posX < 0) posX = 0
        if (posY < 0) posY = 0
        if (posX + widgetWidth(ctx) > ctx.scaledWidth) posX = ctx.scaledWidth - widgetWidth(ctx)
        if (posY + widgetHeight(ctx) > ctx.scaledHeight) posY = ctx.scaledHeight - widgetHeight(ctx)
    }

    // ═══════════════════════════════════════════
    //  Drawing — 4 lines: IN xyz / IN D+cut / OUT xyz / OUT D
    // ═══════════════════════════════════════════

    private fun lines(): List<String> {
        val inVec = "IN %.2f %.2f %.2f".format(inMotionX, inMotionY, inMotionZ)
        val orig = EventBridge.lastKbOriginalSpeed
        val mod = EventBridge.lastKbModifiedSpeed
        // The Velocity retain/cut % only makes sense while the readout is fresh (a knockback is
        // being displayed); once it resets to zero, drop the suffix.
        val cutText = if (inKbNano != 0L && orig > 0.001) {
            val retain = (mod / orig * 100).toInt()
            "D %.2f | %s/%s".format(inDistance, retain, 100 - retain)
        } else {
            "D %.2f".format(inDistance)
        }
        val outVec = "OUT %.2f %.2f %.2f".format(outMotionX, outMotionY, outMotionZ)
        val outD = "D %.2f".format(outDistance)
        return listOf(inVec, cutText, outVec, outD, ratioText())
    }

    /**
     * OUT / IN(effective) horizontal impulse, or -1 when there is nothing to compare.
     *
     * The IN impulse is reduced by our own Velocity factor first, so the number is "what he ate
     * versus what I ate" rather than "two raw packets". The factor is only applied when the packet
     * that produced [inMotionX] is the same one `recordKnockback` saw — otherwise the fields belong
     * to different hits and their ratio would be invented. With Velocity off, or on a pass-through
     * mode, the factor is 1 and the line is the plain formula comparison.
     */
    private fun ratioValue(): Double {
        if (inKbNano == 0L || outKbNano == 0L) return -1.0
        val inH = sqrt(inMotionX * inMotionX + inMotionZ * inMotionZ)
        val outH = sqrt(outMotionX * outMotionX + outMotionZ * outMotionZ)
        if (inH < 0.001 || outH < 0.001) return -1.0

        val samePacket = EventBridge.lastKnockbackNano != 0L &&
            kotlin.math.abs(EventBridge.lastKnockbackNano - inKbNano) < 50_000_000L
        val retain = if (samePacket && EventBridge.lastKbOriginalSpeed > 0.001) {
            (EventBridge.lastKbModifiedSpeed / EventBridge.lastKbOriginalSpeed).coerceIn(0.0, 1.0)
        } else 1.0

        val effectiveIn = inH * retain
        if (effectiveIn < 0.001) return -1.0
        return outH / effectiveIn
    }

    private fun ratioText(): String {
        val r = ratioValue()
        return if (r < 0.0) "R --" else "R %.2fx".format(r)
    }

    private fun ratioColor(): Int {
        val r = ratioValue()
        return when {
            r < 0.0 -> 0xC0C0C0
            r >= 1.0 -> 0xFF22D3EE.toInt()   // we deal more than we take
            else -> 0xFFFF3B30.toInt()       // we take more than we deal
        }
    }

    private fun widgetWidth(ctx: RenderContext): Int {
        val f = ctx.fontRenderer
        val maxLine = lines().maxByOrNull { f.getStringWidth(it) }?.let { f.getStringWidth(it) } ?: 80
        return (maxLine * scale).toInt() + 2
    }

    private fun widgetHeight(ctx: RenderContext): Int {
        return ((ctx.fontRenderer.fontHeight * 5 + 5) * scale).toInt()
    }

    private fun draw(ctx: RenderContext) {
        val f = ctx.fontRenderer
        val g = ctx.gl
        val lineH = f.fontHeight + 2
        val ls = lines()
        // IN line is accent-colored when Velocity modified the last packet, else white.
        val inColor = if (EventBridge.velocityModified) 0xFF22D3EE.toInt() else 0xFFFFFF
        // Apply the Scale option for real: scale the modelview, draw at posX/scale so the text
        // lands at posX..posX+width*scale (matching the drag hitbox).
        g.glPushMatrix()
        g.glScalef(scale, scale, 1f)
        val x = (posX / scale).toInt()
        val y = (posY / scale).toInt()
        f.drawStringWithShadow(ls[0], x, y, inColor)
        f.drawStringWithShadow(ls[1], x, y + lineH, 0xC0C0C0)
        f.drawStringWithShadow(ls[2], x, y + lineH * 2, 0xFFFFFF)
        f.drawStringWithShadow(ls[3], x, y + lineH * 3, 0xC0C0C0)
        f.drawStringWithShadow(ls[4], x, y + lineH * 4, ratioColor())
        g.glPopMatrix()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {
        inMeasuring = false
        inDistance = 0.0
        inLastKbNano = 0L
        outMeasuring = false
        outDistance = 0.0
        outLastKbNano = 0L
        EventBridge.registerVelocityNotifier(velocityNotifier)
        EventBridge.registerAttackListener(attackListener)
        EventBridge.registerEntityVelocityNotifier(entityVelocityNotifier)
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterVelocityNotifier(velocityNotifier)
        EventBridge.unregisterAttackListener(attackListener)
        EventBridge.unregisterEntityVelocityNotifier(entityVelocityNotifier)
        EventBridge.unregisterTickListener(tickListener)
        inMeasuring = false
        inDistance = 0.0
        outMeasuring = false
        outDistance = 0.0
    }
}
