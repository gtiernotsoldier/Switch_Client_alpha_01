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

    // ── Panel geometry (draggable) ──
    @Volatile var panelX: Int = 40
        private set
    @Volatile var panelY: Int = 30
        private set
    private const val PANEL_WIDTH = 190
    private const val CATEGORY_GAP = 4

    private var dragging = false
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var wasLeftDown = false

    /** A tappable module row inside the panel. */
    data class ModuleRow(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val module: Module
    )

    /** Height of the draggable title bar. */
    fun titleBarHeight(lineHeight: Int): Int = lineHeight + 2

    /**
     * Category-grouped layout: list of (category, module rows).
     * Rows are computed in the same coordinate space the overlay renders in,
     * so hit-testing and rendering always agree.
     */
    fun layout(scaledWidth: Int, scaledHeight: Int, lineHeight: Int): List<Pair<Category, List<ModuleRow>>> {
        val result = mutableListOf<Pair<Category, List<ModuleRow>>>()
        var y = panelY + titleBarHeight(lineHeight)
        for (cat in Category.values()) {
            val modules = ModuleRegistry.getByCategory(cat).filter { !it.hidden }
            if (modules.isEmpty()) continue
            val rows = modules.map { m ->
                ModuleRow(panelX + 8, y, PANEL_WIDTH - 16, lineHeight, m)
            }
            result.add(cat to rows)
            y += lineHeight + 2 + rows.size * lineHeight + CATEGORY_GAP
        }
        return result
    }

    /** Total panel height (title bar + categories + footer). */
    fun panelHeight(lineHeight: Int): Int {
        var h = titleBarHeight(lineHeight)
        for (cat in Category.values()) {
            val modules = ModuleRegistry.getByCategory(cat).filter { !it.hidden }
            if (modules.isEmpty()) continue
            h += lineHeight + 2 + modules.size * lineHeight + CATEGORY_GAP
        }
        return h + 8 + 16
    }

    /**
     * Handle mouse input while the GUI is open.
     * - Click on the title bar: start dragging the panel.
     * - Move while dragging: reposition the panel.
     * - Click on a module row: toggle the module.
     * Coordinates are scaled GUI pixels (left-top origin, y down).
     */
    fun handleMouseInput(
        x: Int,
        y: Int,
        leftDown: Boolean,
        scaledWidth: Int,
        scaledHeight: Int,
        lineHeight: Int
    ) {
        if (!isOpen) {
            wasLeftDown = false
            dragging = false
            return
        }
        val clicked = leftDown && !wasLeftDown
        val released = !leftDown && wasLeftDown
        wasLeftDown = leftDown

        if (leftDown) {
            if (clicked) {
                val barBottom = panelY + titleBarHeight(lineHeight)
                if (y >= panelY && y < barBottom) {
                    // Drag start
                    dragging = true
                    dragOffsetX = x - panelX
                    dragOffsetY = y - panelY
                } else {
                    // Module row hit-test
                    for ((_, rows) in layout(scaledWidth, scaledHeight, lineHeight)) {
                        for (row in rows) {
                            if (x >= row.x && x < row.x + row.width && y >= row.y && y < row.y + row.height) {
                                row.module.toggle()
                                notifyModuleToggled(row.module.name, row.module.enabled)
                                return
                            }
                        }
                    }
                }
            } else if (dragging) {
                panelX = (x - dragOffsetX).coerceIn(0, (scaledWidth - PANEL_WIDTH - 10).coerceAtLeast(0))
                panelY = (y - dragOffsetY).coerceIn(0, (scaledHeight - 40).coerceAtLeast(0))
            }
        }
        if (released) {
            dragging = false
        }
    }

    /** Whether the mouse is hovering the panel area (title + body). */
    fun isHoveringPanel(x: Int, y: Int, lineHeight: Int): Boolean {
        val h = panelHeight(lineHeight)
        return x in panelX - 6..panelX + PANEL_WIDTH + 6 && y in panelY - 6..panelY + h
    }


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
}

/**
 * Push a notification when a module is toggled.
 * Called by modules or the GUI after a toggle event.
 */
fun ClickGUI.notifyModuleToggled(moduleName: String, enabled: Boolean) {
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
fun ClickGUI.notifyInjectionResult(success: Boolean) {
    val type = if (success) EventBridge.NotificationType.SUCCESS
               else EventBridge.NotificationType.ERROR
    EventBridge.pushNotification(
        if (success) "SwitchLite injected!" else "Injection failed!",
        type
    )
}
