package io.switchlite.adapter.common.render

/**
 * World-space render data shared between the version-agnostic module layer and the
 * platform adapters. Pure data — zero Minecraft imports (mapping-driven collection).
 *
 * The HitBox module (adapter/common/module/render/HitBox.kt) consumes a [HitBoxFrame]
 * per frame: the platform adapter (ForgeEventBridge) collects it on the render thread
 * inside the world-render pass (EntityRenderer.renderWorldPass hook), where the GL
 * projection/modelview/depth-buffer are the real world ones. That is what makes the
 * box overlay align with the scene AND occlude behind walls (not X-ray) without any
 * camera reconstruction.
 */
enum class HitBoxCategory {
    /** Other players (EntityPlayer, excluding self). */
    PLAYER,
    /** Any living entity that is not a player (EntityLivingBase). */
    MOB,
    /** Dropped items (EntityItem). */
    ITEM,
    /** The player's own entity. */
    OWN
}

/**
 * One entity's render-ready box for the HitBox overlay.
 *
 * The platform provider applies partial-tick interpolation: [renderPos] is the
 * interpolated feet position and [boxMin]/[boxMax] the interpolated bounding box
 * (the real box shifted by the interpolation delta). HitBox uses the real box for
 * the "1.8" mode and rebuilds the box from [renderPos] + the 1.7 size table for
 * the "1.7" mode.
 */
data class HitBoxEntity(
    val entityId: Int,
    val category: HitBoxCategory,
    /** Runtime class simple name (e.g. "EntityZombie") — key for the 1.7 size table. */
    val className: String,
    val renderPosX: Double,
    val renderPosY: Double,
    val renderPosZ: Double,
    val boxMinX: Double,
    val boxMinY: Double,
    val boxMinZ: Double,
    val boxMaxX: Double,
    val boxMaxY: Double,
    val boxMaxZ: Double
) {
    val centerX: Double get() = (boxMinX + boxMaxX) / 2.0
    val centerY: Double get() = (boxMinY + boxMaxY) / 2.0
    val centerZ: Double get() = (boxMinZ + boxMaxZ) / 2.0
}

/**
 * One frame of HitBox data: the viewer (camera entity) position for the distance
 * filter, plus every entity box collected in the world-render pass.
 */
data class HitBoxFrame(
    val viewerX: Double,
    val viewerY: Double,
    val viewerZ: Double,
    val entities: List<HitBoxEntity>
)
