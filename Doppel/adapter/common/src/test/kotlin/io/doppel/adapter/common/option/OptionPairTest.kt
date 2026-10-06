package io.doppel.adapter.common.option

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Min/Max option pairs are kept ordered.
 *
 * Every ranged module feeds its two options straight into `Random.nextInt(min, max + 1)`, which
 * **throws** when `min > max` — a whole-tick crash, not just a wrong number. Nothing stopped the
 * WebUI or a hand-edited config from inverting a pair before this.
 */
class OptionPairTest {

    @Test
    fun `the sibling is derived from the last min-or-max token`() {
        assertEquals(OptionPair("HorizontalMax", isMin = true), optionPairOf("HorizontalMin"))
        assertEquals(OptionPair("HorizontalMin", isMin = false), optionPairOf("HorizontalMax"))
        assertEquals(OptionPair("MinCPS", isMin = false), optionPairOf("MaxCPS"))
        assertEquals(OptionPair("MaxCPS", isMin = true), optionPairOf("MinCPS"))
        // The trailing token names the side, not the leading one.
        assertEquals(OptionPair("MaxSneakTimeMax", isMin = true), optionPairOf("MaxSneakTimeMin"))
        assertEquals(OptionPair("MaxSneakTimeMin", isMin = false), optionPairOf("MaxSneakTimeMax"))
        // A suffix after the token survives.
        assertEquals(OptionPair("ActionMsMax18", isMin = true), optionPairOf("ActionMsMin18"))
        assertEquals(OptionPair("ActionMsMin19", isMin = false), optionPairOf("ActionMsMax19"))
        // The Reach module names its options exactly "Min" and "Max".
        assertEquals(OptionPair("Max", isMin = true), optionPairOf("Min"))
        assertEquals(OptionPair("Min", isMin = false), optionPairOf("Max"))
    }

    @Test
    fun `a name with no partner is left alone`() {
        assertNull(optionPairOf("Chance"))
        assertNull(optionPairOf("CombatVersion"))
        assertNull(optionPairOf("OnlyOnHitFrame"))
        // These do carry the token, but no such sibling is registered anywhere — the pair lookup
        // simply finds nothing, which is the intended "no-op" path.
        assertEquals("MaxFov", optionPairOf("MinFov")!!.partner)
        assertEquals("MinChance", optionPairOf("MaxChance")!!.partner)
    }

    /// Every real Min/Max name in the module tree resolves to the name its module actually declares.
    @Test
    fun `the module tree's pairs all resolve`() {
        val pairs = listOf(
            "HorizontalMin" to "HorizontalMax",
            "VerticalMin" to "VerticalMax",
            "ClicksMin" to "ClicksMax",
            "NthMin" to "NthMax",
            "MinCPS" to "MaxCPS",
            "WaitMsMin" to "WaitMsMax",
            "HitPerMin" to "HitPerMax",
            "PostDelayMin" to "PostDelayMax",
            "OnceEveryMin" to "OnceEveryMax",
            "ActionMsMin18" to "ActionMsMax18",
            "ActionMsMin19" to "ActionMsMax19",
            "TapMsMin" to "TapMsMax",
            "GapMsMin" to "GapMsMax",
            "MinDelayTicks" to "MaxDelayTicks",
            "MinReach" to "MaxReach",
            "MinKeep" to "MaxKeep",
            "MinOffset" to "MaxOffset",
            "MinDistance" to "MaxDistance",
            "RangeMin" to "RangeMax",
            "MaxSneakTimeMin" to "MaxSneakTimeMax",
        )
        for ((min, max) in pairs) {
            assertEquals(max, optionPairOf(min)!!.partner, "$min pairs with $max")
            assertEquals(min, optionPairOf(max)!!.partner, "$max pairs with $min")
            assertTrue(optionPairOf(min)!!.isMin, "$min is the minimum side")
            assertFalse(optionPairOf(max)!!.isMin, "$max is the maximum side")
        }
    }
}
