package io.switchlite.adapter.common.render

import java.awt.Font

/**
 * Loads the bundled smooth font(s) from the agent jar classpath.
 *
 * The TTFs are packaged under `switchlite/fonts/` inside adapter-common's
 * resources, which the agent fat-jar task folds into switchlite-agent.jar.
 * Pure JDK ([java.awt.Font]) — no Minecraft dependency.
 */
object FontFactory {

    private const val REGULAR_PATH = "/switchlite/fonts/regular.ttf"
    private const val ICON_PATH = "/switchlite/fonts/icon.ttf"

    /**
     * Load [regular.ttf] at the given point size (default 18, which keeps
     * glyph height near vanilla's 9 so ClickGUI geometry still fits).
     *
     * Falls back to the logical sans-serif font if the TTF can't be read.
     */
    fun loadRegular(size: Float = 18f): Font {
        loadFromResource(REGULAR_PATH)?.let {
            return it.deriveFont(Font.PLAIN, size)
        }
        return Font(Font.SANS_SERIF, Font.PLAIN, size.toInt())
    }

    /** Load [icon.ttf] at [size] (for icon glyphs). Falls back to regular. */
    fun loadIcon(size: Float = 18f): Font {
        loadFromResource(ICON_PATH)?.let {
            return it.deriveFont(Font.PLAIN, size)
        }
        return loadRegular(size)
    }

    private fun loadFromResource(path: String): Font? {
        return try {
            val stream = FontFactory::class.java.getResourceAsStream(path)
                ?: return null
            stream.use {
                Font.createFont(Font.TRUETYPE_FONT, it)
            }
        } catch (e: Exception) {
            null
        }
    }
}
