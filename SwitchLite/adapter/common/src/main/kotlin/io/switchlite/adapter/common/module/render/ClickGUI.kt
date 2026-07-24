package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyCode
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module

/**
 * ClickGUI module.
 * Toggle with Right Shift, close with ESC.
 * Only handles key binding and open/close state — rendering is separate.
 */
object ClickGUI : Module("ClickGUI", Category.RENDER) {

    private var isOpen = false

    private val keyListener: (Int, Boolean) -> Unit = { keyCode, pressed ->
        if (!pressed) return@keyListener
        when (keyCode) {
            KeyCode.RIGHT_SHIFT -> {
                isOpen = !isOpen
            }
            KeyCode.ESC -> {
                if (isOpen) isOpen = false
            }
        }
    }

    override fun onEnable() {
        EventBridge.registerKeyListener(keyListener)
    }

    override fun onDisable() {
        EventBridge.unregisterKeyListener(keyListener)
        isOpen = false
    }

    fun isOpen(): Boolean = isOpen
}
