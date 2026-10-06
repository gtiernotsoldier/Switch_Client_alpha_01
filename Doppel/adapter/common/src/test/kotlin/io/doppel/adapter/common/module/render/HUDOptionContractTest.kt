package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.module.ModuleRegistry
import io.doppel.adapter.common.option.ConfigManager
import io.doppel.adapter.common.option.OptionDescriptor
import io.doppel.adapter.common.option.OptionType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Every option the HUD module exposes must actually reach the external overlay.
 *
 * The reported bug was exactly a gap in this: `Position` was tunable in the panel and had no path to
 * the overlay at all, because the state contract had no field carrying it. A per-option test is the
 * only way that stays true — the next option added without a wire path fails here instead of in the
 * user's game.
 */
class HUDOptionContractTest {

    @BeforeEach
    fun setUp() {
        // The HUD module is not registered by a test JVM's bootstrap, and its config listeners are
        // subscribed from its own onEnable — register, then enable, so the real path is exercised.
        if (ModuleRegistry.get(HUD.name) == null) ModuleRegistry.register(HUD)
        HUD.enable()
    }

    @AfterEach
    fun tearDown() {
        HUD.disable()
    }

    /** Everything the overlay is told: the header plus the rows. */
    private fun published(): String =
        HUD.overlayHeader().toString() + "|" + HUD.hudEntries.joinToString(",") { "${it.name}=${it.value}" }

    /** A value for [desc] that differs from [current] and is valid for its declared type. */
    private fun alternative(desc: OptionDescriptor, current: Any?): Any {
        val meta = desc.meta
        return when (desc.type) {
            OptionType.BOOLEAN -> !(current as Boolean)
            OptionType.CHOICES -> meta.choices!!.first { it != current }
            OptionType.INT -> {
                val v = (current as Int) + 1
                if (v <= meta.intRangeMax) v else (current - 1).coerceAtLeast(meta.intRangeMin)
            }
            OptionType.FLOAT -> {
                val v = (current as Float) + 0.1f
                if (v <= meta.rangeMax) v else (current - 0.1f).coerceAtLeast(meta.rangeMin)
            }
            OptionType.COLOR -> if ((current as Int) == 0x112233) 0x445566 else 0x112233
            else -> current!!
        }
    }

    @Test
    fun `the HUD module has options to check`() {
        assertTrue(ConfigManager.optionsOf(HUD.name).isNotEmpty())
    }

    @Test
    fun `changing any HUD option changes what the overlay is told`() {
        for (desc in ConfigManager.optionsOf(HUD.name)) {
            val original = ConfigManager.currentValue(desc.key)
            val before = published()

            ConfigManager.set(desc.key, alternative(desc, original))
            HUD.refreshLines()
            val after = published()
            assertNotEquals(
                before,
                after,
                "${desc.name} is tunable in the panel but nothing about it reaches the overlay"
            )

            ConfigManager.set(desc.key, original)
            HUD.refreshLines()
        }
    }

    @Test
    fun `position reaches the header and the overlay is always length-ordered`() {
        ConfigManager.set("HUD.Position", "Left")
        assertTrue(HUD.overlayHeader().positionLeft, "Position=Left must anchor the list left")
        ConfigManager.set("HUD.Position", "Right")
        assertTrue(!HUD.overlayHeader().positionLeft, "Position=Right is the default anchor")

        // The overlay draws the reference's width staircase unconditionally — longest row first,
        // which is what the user asked for — so nothing here can turn the published order back on.
        assertTrue(
            !HUD.overlayHeader().orderPublished,
            "the length order is the only order the overlay draws"
        )
    }
}
