package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.api.EventBridge
import io.switchlite.adapter.common.api.KeyCode
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.option.ConfigManager
import io.switchlite.adapter.common.option.OptionType
import io.switchlite.adapter.common.ui.Animation

/**
 * ClickGUI module — Nemui-style.
 *
 * Toggle with Right Shift, close with ESC.
 * Each category gets its own draggable, collapsible panel. Module rows
 * can be expanded to reveal their settings (sliders / toggles / mode
 * cycles). All geometry, hit-testing and interaction live here as pure
 * data; OverlayRenderer draws exactly what this object lays out.
 *
 * Interaction priority (per mouse press):
 *   1. Panel title bar            → drag panel
 *   2. Collapse arrow (title bar) → expand / collapse panel
 *   3. Module row body            → expand / collapse settings
 *   4. Module toggle dot (right)  → toggle module
 *   5. Setting item               → slider drag / toggle / mode cycle
 */
object ClickGUI : Module("ClickGUI", Category.RENDER) {

    init {
        hidden = true
        showRedIndicator = false
    }

    /** Panel width — Aurora .card 260px per the HTML reference. */
    const val PANEL_WIDTH = 260

    /** Hit-test size of the per-row module toggle area (Aurora .tgl 60px capsule). */
    const val TOGGLE_DOT_SIZE = 60

    /** Row height (.row-main 26px) — module list rows. */
    const val ROW_HEIGHT = 26

    /** Header height — Aurora .c-head is 32px per the HTML reference. */
    const val TITLE_BAR_HEIGHT = 32

    /** Vertical padding under the header before the first row (.c-body top pad). */
    const val BODY_PAD_TOP = 8

    private var isOpen = false

    // ═══════════════ Panel state ═══════════════

    data class Panel(
        var x: Int,
        var y: Int,
        var expanded: Boolean = true,
        val heightAnim: Animation = Animation(0f),
        /** Aurora card entrance: 0 → 1 (fade + slide + scale). */
        val entrance: Animation = Animation(0f)
    )

    private val panels = mutableMapOf<Category, Panel>()

    /** Panel state for a category (cascade-arranged on first open, compact). */
    fun panel(cat: Category): Panel = panels.getOrPut(cat) {
        val idx = cat.ordinal
        // Tight cascade so panels don't sprawl across the screen
        Panel(14 + idx * 12, 14 + idx * 10)
    }

    /** Height of the draggable title bar. */
    fun titleBarHeight(lineHeight: Int): Int = TITLE_BAR_HEIGHT

    /** Aurora entrance progress (0..1) for a category panel. */
    fun entrance(cat: Category): Float = panel(cat).entrance.valueF

    /** Restart the Aurora card entrance animation for all panels. */
    fun resetEntrance() {
        for (p in panels.values) {
            p.entrance.snap(0f)
        }
    }

    // ═══════════════ Setting items ═══════════════

    sealed class SettingItem {
        abstract val key: String
        abstract val name: String
    }

    data class FloatItem(
        override val key: String,
        override val name: String,
        val value: Float,
        val min: Float,
        val max: Float,
        val unit: String
    ) : SettingItem()

    data class IntItem(
        override val key: String,
        override val name: String,
        val value: Int,
        val min: Int,
        val max: Int,
        val unit: String
    ) : SettingItem()

    data class BoolItem(
        override val key: String,
        override val name: String,
        val value: Boolean
    ) : SettingItem()

    data class ChoiceItem(
        override val key: String,
        override val name: String,
        val value: String,
        val choices: List<String>
    ) : SettingItem()

    data class EnumItem(
        override val key: String,
        override val name: String,
        val value: String,
        val choices: List<String>
    ) : SettingItem()

    // ═══════════════ Module rows ═══════════════

    /** One module row. When [expanded], its settings are laid out below. */
    data class ModuleRow(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val module: Module,
        val expanded: Boolean,
        val settings: List<SettingItem>
    ) {
        val toggleDotX: Int get() = x + width - TOGGLE_DOT_SIZE - 2
        val toggleDotY: Int get() = y + (height - TOGGLE_DOT_SIZE) / 2
    }

    /** Modules whose settings are currently expanded (by module name). */
    private val expandedModules = mutableSetOf<String>()

    fun isModuleExpanded(module: Module): Boolean = module.name in expandedModules

    /**
     * Build the settings list of a module from ConfigManager introspection.
     * Sliders/toggles/cycles are rebuilt on every layout so values stay live.
     */
    fun settingsOf(module: Module): List<SettingItem> {
        return ConfigManager.optionsOf(module.name).mapNotNull { desc ->
            when (desc.type) {
                OptionType.FLOAT -> {
                    val def = desc.meta.default as? Float ?: 0f
                    FloatItem(
                        key = desc.key,
                        name = desc.name,
                        value = ConfigManager.read(desc.key, def),
                        min = desc.meta.rangeMin,
                        max = desc.meta.rangeMax,
                        unit = desc.meta.unit
                    )
                }
                OptionType.INT -> {
                    val def = desc.meta.default as? Int ?: 0
                    IntItem(
                        key = desc.key,
                        name = desc.name,
                        value = ConfigManager.read(desc.key, def),
                        min = desc.meta.intRangeMin,
                        max = desc.meta.intRangeMax,
                        unit = desc.meta.unit
                    )
                }
                OptionType.BOOLEAN -> BoolItem(
                    key = desc.key,
                    name = desc.name,
                    value = ConfigManager.read(desc.key, desc.meta.default as? Boolean ?: false)
                )
                OptionType.CHOICES -> {
                    val def = desc.meta.default as? String ?: return@mapNotNull null
                    ChoiceItem(
                        key = desc.key,
                        name = desc.name,
                        value = ConfigManager.read(desc.key, def),
                        choices = desc.meta.displayChoices() ?: emptyList()
                    )
                }
                OptionType.ENUM -> {
                    val def = desc.meta.default as? Enum<*> ?: return@mapNotNull null
                    val names = def.javaClass.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
                    val current = ConfigManager.read<Enum<*>>(desc.key, def)
                    EnumItem(desc.key, desc.name, current.name, names)
                }
                // STRING / TRIGGER_OPTIONS / PROBABILITY: no interactive widget (yet).
                else -> null
            }
        }
    }

    // ═══════════════ Layout ═══════════════

    /**
     * Module rows for ONE category panel, including expanded settings rows.
     * Y positions are absolute and shared with OverlayRenderer for hit-testing.
     */
    fun layout(cat: Category, lineHeight: Int): List<ModuleRow> {
        val pos = panel(cat)
        val modules = ModuleRegistry.getByCategory(cat).filter { !it.hidden }
        var y = pos.y + titleBarHeight(lineHeight) + BODY_PAD_TOP
        return modules.map { m ->
            val expanded = m.name in expandedModules
            val settings = if (expanded) settingsOf(m) else emptyList()
            val row = ModuleRow(pos.x + 8, y, PANEL_WIDTH - 16, lineHeight, m, expanded, settings)
            y += lineHeight + settings.size * lineHeight
            row
        }
    }

    /** Full height of one panel (title + rows + expanded settings + footer pad). */
    fun panelHeight(cat: Category, lineHeight: Int): Int {
        var h = titleBarHeight(lineHeight) + BODY_PAD_TOP
        for (row in layout(cat, lineHeight)) {
            h += row.height + row.settings.size * lineHeight
        }
        return h + 12
    }

    /** All non-empty category panels with their rows. */
    fun categoriesWithRows(lineHeight: Int): List<Pair<Category, List<ModuleRow>>> {
        return Category.values()
            .filter { cat -> ModuleRegistry.getByCategory(cat).any { !it.hidden } }
            .map { cat -> cat to layout(cat, lineHeight) }
    }

    /** Advance panel height animations; call every frame while open. */
    fun tickAnimations(lineHeight: Int) {
        for ((cat, _) in categoriesWithRows(lineHeight)) {
            val p = panel(cat)
            val target = if (p.expanded) panelHeight(cat, lineHeight) - titleBarHeight(lineHeight).toFloat() - BODY_PAD_TOP else 0f
            p.heightAnim.setTarget(target, 16f)
            // Aurora entrance: fade + slide + scale in when the GUI is open.
            p.entrance.setTarget(if (isOpen) 1f else 0f, 10f)
        }
    }

    /** Content clip bottom (y) for a panel — drives the collapse animation. */
    fun contentClipBottom(cat: Category, lineHeight: Int): Int {
        val p = panel(cat)
        return p.y + titleBarHeight(lineHeight) + BODY_PAD_TOP + p.heightAnim.valueI
    }

    // ═══════════════ Drag state ═══════════════

    private var dragging = false
    private var dragCat: Category? = null
    private var dragOffsetX = 0
    private var dragOffsetY = 0
    private var wasLeftDown = false

    /** Active slider drag (settings panel). */
    private data class SliderDrag(
        val key: String,
        val isInt: Boolean,
        val min: Float,
        val max: Float,
        val trackStart: Float,
        val trackWidth: Float
    )

    private var sliderDrag: SliderDrag? = null

    // ═══════════════ Mouse input ═══════════════

    /**
     * Handle mouse input while the GUI is open (scaled GUI coords, y down).
     * Priority: title bar drag → collapse arrow → module row body → toggle dot
     * → setting item (slider drag / toggle / mode cycle).
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
            sliderDrag = null
            return
        }

        val clicked = leftDown && !wasLeftDown
        val released = !leftDown && wasLeftDown
        wasLeftDown = leftDown

        // Active slider drag — consume until release.
        val activeSlider = sliderDrag
        if (activeSlider != null) {
            if (leftDown) {
                updateSlider(activeSlider, x)
            }
            if (released) {
                sliderDrag = null
            }
            return
        }

        if (leftDown && clicked) {
            // ① Panel title bar (drag) + collapse arrow
            for ((cat, _) in categoriesWithRows(lineHeight)) {
                val p = panel(cat)
                val barBottom = p.y + titleBarHeight(lineHeight)
                val hitX = x >= p.x - 6 && x < p.x + PANEL_WIDTH + 6
                if (y >= p.y && y < barBottom && hitX) {
                    // Collapse arrow zone (right edge of the title bar)
                    if (x >= p.x + PANEL_WIDTH - 14) {
                        p.expanded = !p.expanded
                        return
                    }
                    dragging = true
                    dragCat = cat
                    dragOffsetX = x - p.x
                    dragOffsetY = y - p.y
                    return
                }
            }

            // ② Module rows (toggle dot first — it sits inside the row area)
            for ((cat, rows) in categoriesWithRows(lineHeight)) {
                val clipBottom = contentClipBottom(cat, lineHeight)
                for (row in rows) {
                    if (row.y + row.height > clipBottom) continue
                    if (x >= row.toggleDotX && x < row.toggleDotX + TOGGLE_DOT_SIZE &&
                        y >= row.toggleDotY && y < row.toggleDotY + TOGGLE_DOT_SIZE
                    ) {
                        row.module.toggle()
                        notifyModuleToggled(row.module.name, row.module.enabled)
                        return
                    }
                    if (x >= row.x && x < row.x + row.width && y >= row.y && y < row.y + row.height) {
                        toggleModuleExpanded(row.module)
                        return
                    }
                }

                // ③ Setting items of expanded rows
                for (row in rows) {
                    if (!row.expanded) continue
                    for ((idx, item) in row.settings.withIndex()) {
                        val iy = row.y + row.height + idx * lineHeight
                        if (iy + lineHeight > clipBottom) continue
                        if (x >= row.x && x < row.x + row.width && y >= iy && y < iy + lineHeight) {
                            handleSettingClick(item, row, x)
                            return
                        }
                    }
                }
            }
        } else if (leftDown && dragging && dragCat != null) {
            val pos = panel(dragCat!!)
            pos.x = (x - dragOffsetX).coerceIn(0, (scaledWidth - PANEL_WIDTH - 10).coerceAtLeast(0))
            pos.y = (y - dragOffsetY).coerceIn(0, (scaledHeight - 40).coerceAtLeast(0))
        }

        if (released) {
            dragging = false
            dragCat = null
        }
    }

    private fun toggleModuleExpanded(module: Module) {
        if (!expandedModules.add(module.name)) {
            expandedModules.remove(module.name)
        }
    }

    private fun handleSettingClick(item: SettingItem, row: ModuleRow, x: Int) {
        when (item) {
            is BoolItem -> ConfigManager.set(item.key, !item.value)
            is ChoiceItem -> {
                val idx = item.choices.indexOf(item.value)
                val next = item.choices[(idx + 1).coerceAtLeast(0) % item.choices.size.coerceAtLeast(1)]
                ConfigManager.set(item.key, next)
            }
            is EnumItem -> {
                val idx = item.choices.indexOf(item.value)
                val next = item.choices[(idx + 1).coerceAtLeast(0) % item.choices.size.coerceAtLeast(1)]
                setEnumValue(item.key, next)
            }
            is FloatItem -> {
                val (trackStart, trackWidth) = sliderTrack(row)
                sliderDrag = SliderDrag(item.key, false, item.min, item.max, trackStart, trackWidth)
                updateSlider(sliderDrag!!, x)
            }
            is IntItem -> {
                val (trackStart, trackWidth) = sliderTrack(row)
                sliderDrag = SliderDrag(item.key, true, item.min.toFloat(), item.max.toFloat(), trackStart, trackWidth)
                updateSlider(sliderDrag!!, x)
            }
        }
    }

    /** Set an ENUM option by the name of its constant. */
    private fun setEnumValue(key: String, name: String) {
        val meta = ConfigManager.getMeta(key) ?: return
        val def = meta.default as? Enum<*> ?: return
        val constant = def.javaClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name } ?: return
        @Suppress("UNCHECKED_CAST")
        ConfigManager.set(key, constant as Enum<*>)
    }

    /** Slider track geometry inside a setting row. Shared with the renderer. */
    fun sliderTrack(row: ModuleRow): Pair<Float, Float> {
        val trackStart = row.x + 74f
        val trackWidth = (row.x + row.width - 30 - trackStart).coerceAtLeast(10f)
        return trackStart to trackWidth
    }

    private fun updateSlider(drag: SliderDrag, x: Int) {
        val ratio = ((x - drag.trackStart) / drag.trackWidth).coerceIn(0f, 1f)
        val raw = drag.min + ratio * (drag.max - drag.min)
        if (drag.isInt) {
            ConfigManager.set(drag.key, raw.toInt())
        } else {
            ConfigManager.set(drag.key, raw)
        }
    }

    // ═══════════════ Key handling ═══════════════

    private val keyListener: (Int, Boolean) -> Unit = label@{ keyCode, pressed ->
        if (!pressed) return@label
        when (keyCode) {
            KeyCode.RIGHT_SHIFT -> {
                isOpen = !isOpen
                // Delegate to the platform: opens/closes the ClickGUI as a real
                // MC GuiScreen, so MC owns the mouse grab / cursor / keyboard.
                EventBridge.notifyGuiOpen(isOpen)
                if (isOpen) {
                    wasLeftDown = false
                    resetEntrance()
                }
            }
            // ESC is handled by MC's GuiScreen (closes itself via
            // displayGuiScreen(null)) — nothing to do here.
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
        EventBridge.notifyGuiOpen(false)
        expandedModules.clear()
        sliderDrag = null
    }

    fun isOpen(): Boolean = isOpen

    /**
     * Toggle the GUI open/closed. Called by the adapter's key-state poller
     * (render thread) when it detects a RIGHT_SHIFT press edge.
     */
    fun toggleFromPoll() {
        isOpen = !isOpen
        EventBridge.notifyGuiOpen(isOpen)
        if (isOpen) {
            wasLeftDown = false
            resetEntrance()
        }
    }

    /**
     * Called by the adapter when MC closes the GuiScreen itself (e.g. ESC,
     * which MC handles internally by displayGuiScreen(null)). Resets the open
     * state WITHOUT re-invoking the guiOpenHandler (avoids a loop).
     */
    fun markClosed() {
        isOpen = false
        EventBridge.isGuiOpen = false
        expandedModules.clear()
        sliderDrag = null
    }
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
