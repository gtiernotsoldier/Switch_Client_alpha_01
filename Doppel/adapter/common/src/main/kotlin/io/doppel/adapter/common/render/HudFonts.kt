package io.doppel.adapter.common.render

/**
 * The HUD v3 font bundle — one [SmoothFontRenderer] atlas per (face, size)
 * pair the new HUD needs. Built lazily on the render thread (GL touches),
 * with an all-or-nothing usability contract: if any atlas fails to upload,
 * [create] returns null and the HUD falls back to the vanilla pixel font
 * with the same layout.
 *
 * Faces: brand UI = Inter SemiBold; numerics = JetBrains Mono SemiBold
 * (both SIL OFL, bundled under /doppel/fonts/). Raster resolution is
 * guiSize × renderScale so glyphs stay crisp at the current GUI scale.
 */
class HudFonts private constructor(
    /** DOPPEL wordmark (10 GUI px). */
    val brand: SmoothFontRenderer,
    /** Module rows + badge label (9 GUI px). */
    val ui: SmoothFontRenderer,
    /** "statistically you." slogan (6.5 GUI px, mono). */
    val slogan: SmoothFontRenderer,
    /** Badge digits + count (8.5 GUI px, mono). */
    val mono: SmoothFontRenderer
) {
    val usable: Boolean
        get() = brand.ready && ui.ready && slogan.ready && mono.ready

    /**
     * Free every atlas texture in the bundle (called when a new bundle
     * replaces this one, e.g. after a GUI scale change). Without this,
     * each rebuild leaks 4 x 4 MB of VRAM and the dead texture names
     * accumulate for the whole session.
     */
    fun release() {
        brand.release()
        ui.release()
        slogan.release()
        mono.release()
    }

    companion object {

        /** GUI-space font sizes (px) — proportions mirror the approved HTML concept. */
        const val SIZE_BRAND = 10f
        const val SIZE_UI = 9f
        const val SIZE_SLOGAN = 6.5f
        const val SIZE_MONO = 8.5f

        /**
         * Build the bundle for the given GUI scale factor. Returns null when any
         * atlas fails (texture upload refused) — callers must treat null as
         * "use the vanilla font" and may retry later (e.g. after scale change).
         */
        fun create(gl: GL11Bridge, renderScale: Int): HudFonts? {
            return try {
                val rs = renderScale.coerceIn(1, 4)
                val brand = SmoothFontRenderer(FontFactory.loadBrand(rs * SIZE_BRAND), gl, SIZE_BRAND, rs)
                val ui = SmoothFontRenderer(FontFactory.loadBrand(rs * SIZE_UI), gl, SIZE_UI, rs)
                val slogan = SmoothFontRenderer(FontFactory.loadMono(rs * SIZE_SLOGAN), gl, SIZE_SLOGAN, rs)
                val mono = SmoothFontRenderer(FontFactory.loadMono(rs * SIZE_MONO), gl, SIZE_MONO, rs)
                // Upload all atlases NOW (render thread) so the first drawn frame
                // already has live textures — no invisible-text window.
                if (!brand.prepare() || !ui.prepare() || !slogan.prepare() || !mono.prepare()) {
                    io.doppel.core.logging.CoreLogger.warn(
                        "[HudFonts] atlas upload failed (brand=${brand.ready} ui=${ui.ready} " +
                            "slogan=${slogan.ready} mono=${mono.ready}) — falling back to vanilla font"
                    )
                    return null
                }
                HudFonts(brand, ui, slogan, mono)
            } catch (e: Exception) {
                io.doppel.core.logging.CoreLogger.warn(
                    "[HudFonts] creation failed: ${e.javaClass.simpleName}: ${e.message} — falling back to vanilla font"
                )
                null
            }
        }
    }
}
