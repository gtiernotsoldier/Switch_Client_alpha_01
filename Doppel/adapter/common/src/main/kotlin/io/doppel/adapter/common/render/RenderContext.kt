package io.doppel.adapter.common.render

/**
 * All data needed for one frame of overlay rendering.
 *
 * Each version bootstrap constructs a RenderContext before calling
 * OverlayRenderer.render(). The bootstrap is responsible for:
 * - Getting the Minecraft instance and extracting display dimensions
 * - Computing the GUI scale and scaled width/height
 * - Wrapping the platform FontRenderer in a FontRendererBridge
 * - Providing the GL11Bridge implementation
 *
 * OverlayRenderer only reads from this context — it never touches
 * Minecraft classes or MappingContext directly.
 */
data class RenderContext(
    /** Scaled screen width (after GUI scale factor) */
    val scaledWidth: Int,
    /** Scaled screen height (after GUI scale factor) */
    val scaledHeight: Int,
    /** Platform-specific FontRenderer wrapper */
    val fontRenderer: FontRendererBridge,
    /** Platform-specific GL11 bridge */
    val gl: GL11Bridge,
    /**
     * GUI scale factor (physical pixels per GUI pixel). Used by the HUD glass
     * badge to convert layout rects into framebuffer coordinates for the
     * downsample blur. Defaults to 1 when unknown.
     */
    val guiScale: Int = 1,
    /**
     * Smooth (TTF atlas) fonts for the HUD card — null when unavailable
     * (construction failed or platform lacks the texture path), in which case
     * the HUD falls back to [fontRenderer] (vanilla pixel font).
     */
    val hudFonts: HudFonts? = null
)
