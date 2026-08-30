package io.doppel.adapter.forge.v1_8_9

import io.doppel.adapter.common.render.FontRendererBridge
import io.doppel.agent.MappingContext
import io.doppel.core.logging.CoreLogger

/**
 * Forge 1.8.9 implementation of [FontRendererBridge].
 *
 * Wraps a Minecraft FontRenderer object (obtained via MappingContext reflection)
 * and delegates drawStringWithShadow / getStringWidth calls to it.
 *
 * If the MappingContext method handle is null (missing mapping key), the method
 * falls back gracefully instead of crashing the render pipeline.
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

    private var drawStringErrorLogged = false
    private var getStringWidthErrorLogged = false

    override fun drawStringWithShadow(text: String, x: Int, y: Int, color: Int): Int {
        return try {
            val handle = drawStringMethod
            if (handle == null) {
                if (!drawStringErrorLogged) {
                    CoreLogger.error("[ForgeFontRendererBridge] drawStringWithShadow MethodHandle is null — mapping key missing?")
                    drawStringErrorLogged = true
                }
                return 0
            }
            handle.bindTo(fontRendererObj).invokeWithArguments(text, x.toFloat(), y.toFloat(), color) as? Int ?: 0
        } catch (e: Exception) {
            if (!drawStringErrorLogged) {
                CoreLogger.error("[ForgeFontRendererBridge] drawStringWithShadow failed: ${e.javaClass.simpleName}: ${e.message}")
                drawStringErrorLogged = true
            }
            0
        }
    }

    override fun getStringWidth(text: String): Int {
        return try {
            val handle = getStringWidthMethod
            if (handle == null) {
                if (!getStringWidthErrorLogged) {
                    CoreLogger.error("[ForgeFontRendererBridge] getStringWidth MethodHandle is null — mapping key missing?")
                    getStringWidthErrorLogged = true
                }
                return text.length * 6
            }
            handle.bindTo(fontRendererObj).invokeWithArguments(text) as? Int ?: text.length * 6
        } catch (e: Exception) {
            if (!getStringWidthErrorLogged) {
                CoreLogger.error("[ForgeFontRendererBridge] getStringWidth failed: ${e.javaClass.simpleName}: ${e.message}")
                getStringWidthErrorLogged = true
            }
            text.length * 6
        }
    }
}
