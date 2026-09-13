package io.doppel.adapter.common.render

import java.awt.Font

/**
 * Loads the bundled smooth font(s) from the agent jar classpath.
 *
 * The TTFs are packaged under `doppel/fonts/` inside adapter-common's
 * resources, which the agent fat-jar task folds into doppel-agent.jar.
 * Pure JDK ([java.awt.Font]) — no Minecraft dependency.
 */
object FontFactory {

    private const val REGULAR_PATH = "/doppel/fonts/regular.ttf"
    private const val ICON_PATH = "/doppel/fonts/icon.ttf"

    /** Brand UI face — Inter SemiBold (#HUD v3: wordmark + module rows). */
    private const val INTER_PATH = "/doppel/fonts/inter-semibold.ttf"

    /** Numeric face — JetBrains Mono SemiBold (#HUD v3: badge digits). */
    private const val MONO_PATH = "/doppel/fonts/jbmono-semibold.ttf"

    /**
     * Brand UI font (Inter SemiBold) at [size]. Falls back to the legacy
     * bundled regular face, then to the logical sans-serif.
     */
    fun loadBrand(size: Float): Font {
        return loadFromResource(INTER_PATH)?.deriveFont(Font.PLAIN, size) ?: loadRegular(size)
    }

    /** Numeric/mono font (JetBrains Mono SemiBold) at [size]. */
    fun loadMono(size: Float): Font {
        return loadFromResource(MONO_PATH)?.deriveFont(Font.PLAIN, size) ?: loadBrand(size)
    }

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
