package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.option.boolean
import io.switchlite.adapter.common.option.choices
import io.switchlite.adapter.common.option.float
import io.switchlite.adapter.common.render.GL11Bridge
import io.switchlite.adapter.common.render.GLConstants
import io.switchlite.adapter.common.render.HitBoxCategory
import io.switchlite.adapter.common.render.HitBoxEntity

/**
 * HitBox — draws entity collision boxes in the world (visual/RENDER category).
 *
 * The platform adapter (ForgeEventBridge) collects a [io.switchlite.adapter.common.render.HitBoxFrame]
 * inside the EntityRenderer.renderWorldPass hook (anchored right before the hand renders) — at that
 * point the GL projection/modelview are the world ones and the depth buffer holds the rendered
 * scene, so the boxes align with the models and are occluded by walls (NOT X-ray). Rendering draws
 * wireframe boxes only: no facing-direction lines, no entity names.
 *
 * Options:
 *   - Mode  "1.8": the entity's REAL bounding box (getEntityBoundingBox).
 *          "1.7": the 1.7.10-era box sizes for entities whose hitbox 1.8 changed (see [SIZE_1_7];
 *          everything else keeps the real box). Verify in-game, then extend the table.
 *   - Players / Mobs / Items / Own: which entity classes get boxes (own = the player itself).
 *   - Per-category NAMED colors (plain words: Green/Red/Blue/...) and line width (thin default).
 *
 * Rendered every world frame, not throttled — the provider does the field reads per frame.
 */
object HitBox : Module("HitBox", Category.RENDER) {

    // ========== Options ==========
    private val mode by choices("Mode", arrayOf("1.8", "1.7"))
    private val players by boolean("Players", true)
    private val mobs by boolean("Mobs", true)
    private val items by boolean("Items", false)
    private val own by boolean("Own", true)
    // Named colors (plain words, no hex needed — same style as Keystrokes' TextColor).
    private val playerColor by choices("PlayerColor", arrayOf("Green", "Red", "Blue", "Yellow", "Purple", "Cyan", "Orange", "White"))
    private val mobColor by choices("MobColor", arrayOf("Red", "Green", "Blue", "Yellow", "Purple", "Cyan", "Orange", "White"))
    private val itemColor by choices("ItemColor", arrayOf("Yellow", "Green", "Red", "Blue", "Purple", "Cyan", "Orange", "White"))
    private val ownColor by choices("OwnColor", arrayOf("Cyan", "Green", "Red", "Blue", "Yellow", "Purple", "Orange", "White"))
    private val lineWidth by float("LineWidth", 1.0f, 1.0f..4.0f)
    private val maxDist by float("MaxDist", 64.0f, 8.0f..256.0f)

    private val PALETTE: Map<String, Int> = mapOf(
        "White" to 0xFFFFFF,
        "Red" to 0xFF3030,
        "Green" to 0x00E64D,
        "Blue" to 0x30A0FF,
        "Yellow" to 0xFFD000,
        "Purple" to 0xB040FF,
        "Cyan" to 0x00E5FF,
        "Orange" to 0xFF7A00
    )

    /**
     * 1.7.10 constructor sizes (width × height) for entities whose hitbox changed in 1.8.
     * The 1.8.9 runtime sizes are read live from the game:
     *   - EntityZombie : 1.8.9 = 0.6 × 1.95, 1.7.10 = 0.6 × 1.8  (1.8 zombie model taller)
     *   - EntityCreeper: 1.8.9 = 0.6 × 1.7,  1.7.10 = 0.6 × 1.8  (1.8 creeper model shorter)
     * Entities NOT listed keep the real (1.8) box in both modes — most sizes did not change.
     * VERIFY IN GAME first (a zombie/creeper in 1.7 mode should differ by height), then add more.
     */
    private val SIZE_1_7: Map<String, Pair<Double, Double>> = mapOf(
        "EntityZombie" to (0.6 to 1.8),
        "EntityCreeper" to (0.6 to 1.8)
    )

    private data class Box(
        val x1: Double, val y1: Double, val z1: Double,
        val x2: Double, val y2: Double, val z2: Double
    )

    // ========== Entry point (called by the platform's renderWorldPass hook) ==========
    /** Diagnostic counters (throttled logging so the render loop never spams). */
    private var diagFrame = 0
    private var diagNoFrameLogged = false

    fun renderWorld(gl: GL11Bridge) {
        if (!enabled) return
        val frame = EventBridge.getHitBoxFrame()
        if (frame == null) {
            if (!diagNoFrameLogged) {
                diagNoFrameLogged = true
                io.switchlite.core.logging.CoreLogger.warn("[HitBox] enabled but no frame provider (world hook alive, provider missing?)")
            }
            return
        }
        val entities = frame.entities
        if (++diagFrame % 100 == 0) {
            io.switchlite.core.logging.CoreLogger.info("[HitBox] world hook alive, entities=${entities.size}")
        }
        if (entities.isEmpty()) return

        gl.glPushAttrib(GLConstants.GL_ALL_ATTRIB_BITS)
        try {
            gl.glEnable(GLConstants.GL_DEPTH_TEST)
            gl.glDepthMask(false) // never write depth — boxes must not occlude each other
            gl.glDisable(GLConstants.GL_TEXTURE_2D)
            gl.glDisable(GLConstants.GL_LIGHTING)
            gl.glEnable(GLConstants.GL_BLEND)
            gl.glBlendFunc(GLConstants.GL_SRC_ALPHA, GLConstants.GL_ONE_MINUS_SRC_ALPHA)
            if (lineWidth > 1.0f) gl.glLineWidth(lineWidth)

            val maxDistSq = maxDist * maxDist
            var drawn = 0
            for (e in entities) {
                val color = colorFor(e) ?: continue
                val dx = e.centerX - frame.viewerX
                val dy = e.centerY - frame.viewerY
                val dz = e.centerZ - frame.viewerZ
                if (dx * dx + dy * dy + dz * dz > maxDistSq) continue
                drawBox(gl, resolveBox(e), color)
                drawn++
            }
            if (diagFrame % 100 == 0 && drawn == 0) {
                io.switchlite.core.logging.CoreLogger.info("[HitBox] entities=${entities.size} but 0 drawn (filters?)")
            }
        } finally {
            gl.glPopAttrib()
        }
    }

    // ========== Box resolution ==========
    private fun resolveBox(e: HitBoxEntity): Box {
        if (mode == "1.7") {
            val size = SIZE_1_7[e.className]
            if (size != null) {
                val w = size.first / 2.0
                val h = size.second
                // 1.7-style box centered on the (interpolated) feet position.
                return Box(
                    e.renderPosX - w, e.renderPosY, e.renderPosZ - w,
                    e.renderPosX + w, e.renderPosY + h, e.renderPosZ + w
                )
            }
        }
        return Box(e.boxMinX, e.boxMinY, e.boxMinZ, e.boxMaxX, e.boxMaxY, e.boxMaxZ)
    }

    private fun colorFor(e: HitBoxEntity): Int? = when (e.category) {
        HitBoxCategory.PLAYER -> if (players) PALETTE[playerColor] else null
        HitBoxCategory.MOB -> if (mobs) PALETTE[mobColor] else null
        HitBoxCategory.ITEM -> if (items) PALETTE[itemColor] else null
        HitBoxCategory.OWN -> if (own) PALETTE[ownColor] else null
    }

    // ========== Wireframe drawing (12 edges, GL_LINES) ==========
    private fun drawBox(gl: GL11Bridge, b: Box, color: Int) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val bl = (color and 0xFF) / 255f
        val x1 = b.x1.toFloat(); val y1 = b.y1.toFloat(); val z1 = b.z1.toFloat()
        val x2 = b.x2.toFloat(); val y2 = b.y2.toFloat(); val z2 = b.z2.toFloat()

        gl.glColor4f(r, g, bl, 1.0f)
        gl.glBegin(GLConstants.GL_LINES)

        // bottom face
        gl.glVertex3f(x1, y1, z1); gl.glVertex3f(x2, y1, z1)
        gl.glVertex3f(x2, y1, z1); gl.glVertex3f(x2, y1, z2)
        gl.glVertex3f(x2, y1, z2); gl.glVertex3f(x1, y1, z2)
        gl.glVertex3f(x1, y1, z2); gl.glVertex3f(x1, y1, z1)
        // top face
        gl.glVertex3f(x1, y2, z1); gl.glVertex3f(x2, y2, z1)
        gl.glVertex3f(x2, y2, z1); gl.glVertex3f(x2, y2, z2)
        gl.glVertex3f(x2, y2, z2); gl.glVertex3f(x1, y2, z2)
        gl.glVertex3f(x1, y2, z2); gl.glVertex3f(x1, y2, z1)
        // vertical edges
        gl.glVertex3f(x1, y1, z1); gl.glVertex3f(x1, y2, z1)
        gl.glVertex3f(x2, y1, z1); gl.glVertex3f(x2, y2, z1)
        gl.glVertex3f(x2, y1, z2); gl.glVertex3f(x2, y2, z2)
        gl.glVertex3f(x1, y1, z2); gl.glVertex3f(x1, y2, z2)

        gl.glEnd()
    }

    // ========== Lifecycle ==========
    override fun onEnable() {}
    override fun onDisable() {}
}
