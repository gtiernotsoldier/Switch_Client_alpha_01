package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyCode
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry

/**
 * ClickGUI module.
 *
 * Toggle with Right Shift, close with ESC.
 * Each module category gets its OWN draggable panel (Combat, Movement,
 * Player, Render, World...), like mainstream clients. The overlay renders
 * the panels; this module owns geometry, layout, hit-testing and dragging.
 *
 * Panel positions live in [panelPositions] and are draggable by their title
 * bars. Module rows are click-to-toggle; hit-testing uses the exact same
 * [layout] rects that OverlayRenderer draws.
 */
object ClickGUI : Module("ClickGUI", Category.RENDER) {

    init {
        hidden = true
        showRedIndicator = false
    }

    /** Panel width for every category panel. */
    const val PANEL_WIDTH = 190

    private var isOpen = false

    // ── Per-category panel geometry ──
    data class PanelPos(var x: Int, var y: Int)

    private val panelPositions = mutableMapOf<Category, PanelPos>()

    /** Panel position for a category (cascade-arranged on first open). */
    fun panelPos(cat: Category): PanelPos = panelPositions.getOrPut(cat) {
        val idx = cat.ordinal
        PanelPos(20 + idx * 30, 20 + idx * 24)
    }

    /** Height of the draggable title bar. */
    fun titleBarHeight(lineHeight: Int): Int = lineHeight + 2

    /** A tappable module row inside a category panel. */
    data class ModuleRow(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val module: Module
    )

    /**
     * Module rows for ONE category panel.
     * y increments per row — fixes the earlier overlap bug.
     */
    fun layout(cat: Category, lineHeight: Int): List<ModuleRow> {
        val pos = panelPos(cat)
        val modules = ModuleRegistry.getByCategory(cat).filter { !it.hidden }
        val startY = pos.y + titleBarHeight(lineHeight)
        return modules.mapIndexed { i, m ->
            ModuleRow(pos.x + 8, startY + i * lineHeight, PANEL_WIDTH - 16, lineHeight, m)
        }
    }

    /** Total height of one category panel (title + rows + footer pad). */
    fun panelHeight(cat: Category, lineHeight: Int): Int {
        val n = ModuleRegistry.getByCategory(cat).filter { !it.hidden }.size
        return titleBarHeight(lineHeight) + n * lineHeight + 12
    }

    /** All non-empty category panels with their rows (render + hit-test). */
    fun categoriesWithRows(lineHeight: Int): List<Pair<Category, List<ModuleRow>>> {
        return Category.values()
            .filter { cat -> ModuleRegistry.getByCategory(cat).any { !it.hidden } }
            .map { cat -> cat to layout(cat, lineHeight) }
    }

    // ── Drag state ──
    private var dragging = false
    private var dragCat: Category? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var wasLeftDown = false

    /**
     * Handle mouse input while the GUI is open (scaled GUI coords, y down).
     * - Click on a panel title bar: drag that panel.
     * - Click on a module row: toggle the module.
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
            dragCat = null
            return
        }
        val clicked = leftDown && !wasLeftDown
        val released = !leftDown && wasLeftDown
        wasLeftDown = leftDown

        if (leftDown) {
            if (clicked) {
                // Title-bar hit across all panels first (drag wins over rows)
                for ((cat, _) in categoriesWithRows(lineHeight)) {
                    val pos = panelPos(cat)
                    val barBottom = pos.y + titleBarHeight(lineHeight)
                    if (y >= pos.y && y < barBottom && x >= pos.x - 6 && x < pos.x + PANEL_WIDTH + 6) {
                        dragging = true
                        dragCat = cat
                        dragOffsetX = x - pos.x
                        dragOffsetY = y - pos.y
                        return
                    }
                }
                // Module row hit across all panels
                for ((_, rows) in categoriesWithRows(lineHeight)) {
                    for (row in rows) {
                        if (x >= row.x && x < row.x + row.width && y >= row.y && y < row.y + row.height) {
                            row.module.toggle()
                            notifyModuleToggled(row.module.name, row.module.enabled)
                            return
                        }
                    }
                }
            } else if (dragging && dragCat != null) {
                val pos = panelPos(dragCat!!)
                pos.x = (x - dragOffsetX).coerceIn(0, (scaledWidth - PANEL_WIDTH - 10).coerceAtLeast(0))
                pos.y = (y - dragOffsetY).coerceIn(0, (scaledHeight - 40).coerceAtLeast(0))
            }
        }
        if (released) {
            dragging = false
            dragCat = null
        }
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
