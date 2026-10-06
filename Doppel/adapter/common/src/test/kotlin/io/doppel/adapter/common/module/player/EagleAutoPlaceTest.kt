package io.doppel.adapter.common.module.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AutoPlace's two gates.
 *
 * The user asked for the placement to happen only when the first two conditions hold — standing on an
 * edge **and** sneaking — and this is that rule: without both, a right-click every one to three ticks
 * is an auto-clicker aimed at the ground, which is precisely what makes it visible.
 */
class EagleAutoPlaceTest {

    @Test
    fun `it fires only with both gates open`() {
        assertTrue(Eagle.shouldAutoPlace(enabled = true, autoPlace = true, onEdge = true, sneaking = true))
    }

    @Test
    fun `no edge, no placement`() {
        assertFalse(Eagle.shouldAutoPlace(enabled = true, autoPlace = true, onEdge = false, sneaking = true))
    }

    @Test
    fun `not sneaking, no placement`() {
        assertFalse(Eagle.shouldAutoPlace(enabled = true, autoPlace = true, onEdge = true, sneaking = false))
    }

    @Test
    fun `neither gate open, and the option or the module off, all refuse`() {
        assertFalse(Eagle.shouldAutoPlace(enabled = true, autoPlace = true, onEdge = false, sneaking = false))
        assertFalse(Eagle.shouldAutoPlace(enabled = true, autoPlace = false, onEdge = true, sneaking = true))
        assertFalse(Eagle.shouldAutoPlace(enabled = false, autoPlace = true, onEdge = true, sneaking = true))
    }
}
