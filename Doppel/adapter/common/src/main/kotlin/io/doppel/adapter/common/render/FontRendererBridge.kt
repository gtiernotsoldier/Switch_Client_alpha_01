package io.doppel.adapter.common.render

/**
 * Abstraction over Minecraft's FontRenderer.
 *
 * Each version adapter wraps its platform-specific FontRenderer object
 * (obtained via reflection / MappingContext) and implements this interface.
 *
 * The consumer (OverlayRenderer) only calls drawStringWithShadow / getStringWidth
 * without knowing whether the underlying renderer is Forge 1.8.9 or Fabric 1.20.1.
 */
interface FontRendererBridge {

    /**
     * Draw text with a drop shadow at the given position.
     * @return the width of the drawn string in pixels
     */
    fun drawStringWithShadow(text: String, x: Int, y: Int, color: Int): Int

    /**
     * Measure the width of a string without drawing it.
     */
    fun getStringWidth(text: String): Int

    /**
     * The height of one line of text in pixels.
     */
    val fontHeight: Int
}
