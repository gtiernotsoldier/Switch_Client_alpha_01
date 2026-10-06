package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.adapter.common.option.float
import io.doppel.adapter.common.option.int
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The derived HUD value of a module that has no [io.doppel.adapter.common.module.HudLineProvider].
 *
 * The reported bug was Velocity showing the midpoint of its range, but Velocity was not the only
 * module with a Min/Max pair — every ranged module without its own provider (WTap, STap, ADTap,
 * BlockHit, Eagle, TriggerBot, …) showed only the first number of its range.
 */
class HUDModuleValueTest {

    private class RangedMs : Module("RangedMs", Category.COMBAT) {
        val waitMin by int("WaitMsMin", 25, 1..500, "ms")
        val waitMax by int("WaitMsMax", 55, 1..500, "ms")
    }

    private class RangedFloat : Module("RangedFloat", Category.COMBAT) {
        val rangeMin by float("RangeMin", 3.0f, 0f..6f, "blocks")
        val rangeMax by float("RangeMax", 3.2f, 0f..6f, "blocks")
    }

    private class LoneNumber : Module("LoneNumber", Category.COMBAT) {
        val chance by int("Chance", 80, 0..100, "%")
    }

    private fun registered(module: Module): Module =
        ModuleRegistry.get(module.name) ?: module.also { ModuleRegistry.register(it) }

    @Test
    fun `a Min Max pair is shown as a range`() {
        val m = registered(RangedMs())
        assertEquals(25, m.let { (it as RangedMs).waitMin })
        assertEquals("25-55 ms", HUD.autoHudValue(m).first)
    }

    @Test
    fun `a float pair keeps its declared precision`() {
        val m = registered(RangedFloat())
        assertEquals("3.00-3.20 blocks", HUD.autoHudValue(m).first)
    }

    @Test
    fun `a module with a single number still shows just it`() {
        val m = registered(LoneNumber())
        assertEquals("80 %", HUD.autoHudValue(m).first)
    }

    @Test
    fun `an equal pair collapses to one value`() {
        val m = registered(RangedMs())
        // The option layer keeps pairs ordered, so setting the minimum to the maximum is legal.
        ConfigManager.set("RangedMs.WaitMsMin", 40)
        ConfigManager.set("RangedMs.WaitMsMax", 40)
        assertEquals("40 ms", HUD.autoHudValue(m).first)

        ConfigManager.set("RangedMs.WaitMsMin", 25)
        ConfigManager.set("RangedMs.WaitMsMax", 55)
    }
}
