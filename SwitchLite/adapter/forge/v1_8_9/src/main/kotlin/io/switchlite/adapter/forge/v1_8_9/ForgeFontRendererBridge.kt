package io.switchlite.adapter.forge.v1_8_9

import io.switchlite.adapter.common.render.FontRendererBridge
import io.switchlite.agent.MappingContext

/**
 * Forge 1.8.9 implementation of [FontRendererBridge].
 *
 * Wraps a Minecraft FontRenderer object (obtained via MappingContext reflection)
 * and delegates drawStringWithShadow / getStringWidth calls to it.
 *
 * @param fontRendererObj the raw Minecraft FontRenderer instance
 */
class ForgeFontRendererBridge(private val fontRendererObj: Any) : FontRendererBridge {

    override val fontHeight: Int by lazy {
        MappingContext.getFieldValue(fontRendererObj, "forge:fontRenderer_FONT_HEIGHT") as? Int ?: 9
    }

    private val drawStringMethod by lazy {
        MappingContext.getMethod("forge:fontRenderer_drawStringWithShadow")
    }

    private val getStringWidthMethod by lazy {
        MappingContext.getMethod("forge:fontRenderer_getStringWidth")
    }

    override fun drawStringWithShadow(text: String, x: Int, y: Int, color: Int): Int {
        return try {
            drawStringMethod.invoke(fontRendererObj, text, x, y, color) as? Int ?: 0
        } catch (_: Exception) {
            0
        }
    }

    override fun getStringWidth(text: String): Int {
        return try {
            getStringWidthMethod.invoke(fontRendererObj, text) as? Int ?: text.length * 6
        } catch (_: Exception) {
            text.length * 6
        }
    }
}
