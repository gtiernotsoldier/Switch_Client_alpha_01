package io.doppel.adapter.common.option

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Min/Max enforcement inside [ConfigManager.set].
 *
 * Setting the minimum above the maximum pulls the maximum up (and the reverse), so a consumer that
 * does `Random.nextInt(min, max + 1)` can never be handed an inverted pair — which used to throw.
 */
class ConfigManagerPairOrderTest {

    private fun registerPair() {
        ConfigManager.register(
            "Pair.HorizontalMin", 0.4f,
            OptionMeta(type = OptionType.FLOAT, default = 0.4f, rangeMin = 0f, rangeMax = 1f)
        )
        ConfigManager.register(
            "Pair.HorizontalMax", 0.6f,
            OptionMeta(type = OptionType.FLOAT, default = 0.6f, rangeMin = 0f, rangeMax = 1f)
        )
    }

    @Test
    fun `raising the minimum above the maximum raises the maximum`() {
        registerPair()
        ConfigManager.set("Pair.HorizontalMin", 0.9f)
        assertEquals(0.9f, ConfigManager.get<Float>("Pair.HorizontalMin"))
        assertEquals(0.9f, ConfigManager.get<Float>("Pair.HorizontalMax"), "the maximum followed")
    }

    @Test
    fun `lowering the maximum below the minimum lowers the minimum`() {
        registerPair()
        ConfigManager.set("Pair.HorizontalMax", 0.1f)
        assertEquals(0.1f, ConfigManager.get<Float>("Pair.HorizontalMax"))
        assertEquals(0.1f, ConfigManager.get<Float>("Pair.HorizontalMin"), "the minimum followed")
    }

    @Test
    fun `an ordered change leaves the sibling alone`() {
        registerPair()
        ConfigManager.set("Pair.HorizontalMin", 0.2f)
        assertEquals(0.6f, ConfigManager.get<Float>("Pair.HorizontalMax"), "0.2 < 0.6, nothing to do")
        ConfigManager.set("Pair.HorizontalMax", 0.8f)
        assertEquals(0.2f, ConfigManager.get<Float>("Pair.HorizontalMin"), "0.8 > 0.2, nothing to do")
    }

    /// The sibling is written at *its own* declared type: a Double landing in a Float option would
    /// blow up later as a ClassCastException inside the module that reads it.
    @Test
    fun `the sibling keeps its declared type`() {
        ConfigManager.register(
            "IntPair.MinDelayTicks", 1,
            OptionMeta(type = OptionType.INT, default = 1, intRangeMin = 1, intRangeMax = 10)
        )
        ConfigManager.register(
            "IntPair.MaxDelayTicks", 4,
            OptionMeta(type = OptionType.INT, default = 4, intRangeMin = 1, intRangeMax = 20)
        )
        ConfigManager.set("IntPair.MinDelayTicks", 7)
        assertEquals(7, ConfigManager.get<Int>("IntPair.MaxDelayTicks"), "read back as an Int")
    }

    /// A name whose partner was never registered is simply not a pair.
    @Test
    fun `a lone option is not touched`() {
        ConfigManager.register(
            "Lone.MinTicks", 80,
            OptionMeta(type = OptionType.INT, default = 80, intRangeMin = 10, intRangeMax = 200)
        )
        ConfigManager.set("Lone.MinTicks", 150)
        assertEquals(150, ConfigManager.get<Int>("Lone.MinTicks"))
    }

    /// A reset bypasses `set`, so it has to apply the pair rule itself.
    @Test
    fun `a reset restores an ordered pair`() {
        registerPair()
        ConfigManager.set("Pair.HorizontalMin", 0.9f) // the maximum follows to 0.9
        assertEquals(0.9f, ConfigManager.get<Float>("Pair.HorizontalMax"))

        ConfigManager.resetOption("Pair.HorizontalMax") // back to its 0.6 default
        assertEquals(0.6f, ConfigManager.get<Float>("Pair.HorizontalMax"))
        assertEquals(0.6f, ConfigManager.get<Float>("Pair.HorizontalMin"), "the minimum followed")
    }
}
