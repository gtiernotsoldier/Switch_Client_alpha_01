package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyCode
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry

/**
 * ClickGUI module.
 * Toggle with Right Shift, close with ESC.
 * Handles key binding and open/close state — rendering is done by the adapter layer.
 *
 * When opened, sets EventBridge.isGuiOpen = true so the adapter render hook
 * draws the GUI overlay. When closed, restores it to false.
 * Also pushes notifications to EventBridge when modules are toggled via the GUI
 * (the adapter render hook draws these in the bottom-right corner).
 */
object ClickGUI : Module("ClickGUI", Category.RENDER) {

    init {
        // ClickGUI itself is always hidden from HUD — no point showing it
        hidden = true
        showRedIndicator = false
    }

    private var isOpen = false

    private val keyListener: (Int, Boolean) -> Unit = label@{ keyCode, pressed ->
        if (!pressed) return@label
        when (keyCode) {
            KeyCode.RIGHT_SHIFT -> {
                isOpen = !isOpen
                EventBridge.isGuiOpen = isOpen
            }
            KeyCode.ESC -> {
                if (isOpen) {
                    isOpen = false
                    EventBridge.isGuiOpen = false
                }
            }
        }
    }

    /**
     * Dispatch a key event for module toggle.
     * Called by the adapter when a key is pressed and the GUI is open,
     * so the GUI can handle module keybind changes.
     * Returns true if the key was consumed by the GUI.
     */
    fun handleModuleKeybind(keyCode: Int): Boolean {
        if (!isOpen) return false
        return ModuleRegistry.getAll().any { it.tryKeybindToggle(keyCode) }
    }

    override fun onEnable() {
        EventBridge.registerKeyListener(keyListener)
    }

    override fun onDisable() {
        EventBridge.unregisterKeyListener(keyListener)
        isOpen = false
        EventBridge.isGuiOpen = false
    }

    fun isOpen(): Boolean = isOpen

    companion object {
        /**
         * Push a notification when a module is toggled.
         * Called by modules or the GUI after a toggle event.
         */
        fun notifyModuleToggled(moduleName: String, enabled: Boolean) {
            val type = if (enabled) EventBridge.NotificationType.SUCCESS
                       else EventBridge.NotificationType.ERROR
            EventBridge.pushNotification(
                "$moduleName ${if (enabled) "ON" else "OFF"}",
                type
            )
        }

        /**
         * Push a notification for injection status.
         * Called by the agent layer on injection success/failure.
         */
        fun notifyInjectionResult(success: Boolean) {
            val type = if (success) EventBridge.NotificationType.SUCCESS
                       else EventBridge.NotificationType.ERROR
            EventBridge.pushNotification(
                if (success) "SwitchLite injected!" else "Injection failed!",
                type
            )
        }
    }
}
