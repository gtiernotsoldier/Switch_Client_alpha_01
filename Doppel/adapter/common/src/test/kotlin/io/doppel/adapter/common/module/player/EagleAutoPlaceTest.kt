package io.doppel.adapter.common.module.player

import io.doppel.adapter.common.module.player.Eagle.PlaceStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AutoPlace's gates and its aim-first rule.
 *
 * Two things the user reported: placements kept happening without the downward aim, and AutoPlace
 * fought BridgeAssist for the camera. The first is why a click now requires the view to already be
 * inside the tolerance; the second is why AIM is refused outright while BridgeAssist holds the view.
 */
class EagleAutoPlaceTest {

    @Test
    fun `it fires only with both gates open`() {
        assertTrue(Eagle.shouldAutoPlace(enabled = true, autoPlace = true, onEdge = true, sneaking = true))
        assertFalse(Eagle.shouldAutoPlace(true, true, onEdge = false, sneaking = true), "no edge")
        assertFalse(Eagle.shouldAutoPlace(true, true, onEdge = true, sneaking = false), "not sneaking")
        assertFalse(Eagle.shouldAutoPlace(true, false, onEdge = true, sneaking = true), "option off")
        assertFalse(Eagle.shouldAutoPlace(false, true, onEdge = true, sneaking = true), "module off")
    }

    @Test
    fun `a click needs the view to already be at the bridging angle`() {
        assertEquals(PlaceStep.CLICK, Eagle.placeDecision(78f, 78f, 6f, 0, 3, bridgeAssistEnabled = false))
        assertEquals(PlaceStep.CLICK, Eagle.placeDecision(74f, 78f, 6f, 0, 3, bridgeAssistEnabled = false))
        assertEquals(
            PlaceStep.AIM,
            Eagle.placeDecision(20f, 78f, 6f, 0, 3, bridgeAssistEnabled = false),
            "looking at the horizon: aim first, never click"
        )
    }

    @Test
    fun `an aim that never arrives is abandoned without clicking`() {
        assertEquals(PlaceStep.ABORT, Eagle.placeDecision(20f, 78f, 6f, 3, 3, bridgeAssistEnabled = false))
        assertEquals(PlaceStep.ABORT, Eagle.placeDecision(20f, 78f, 6f, 9, 3, bridgeAssistEnabled = false))
    }

    @Test
    fun `BridgeAssist owns the view, so AutoPlace never fights it for the aim`() {
        assertEquals(
            PlaceStep.ABORT,
            Eagle.placeDecision(20f, 78f, 6f, 0, 3, bridgeAssistEnabled = true),
            "BridgeAssist is on and not at the angle: stand down rather than write the rotation"
        )
        assertEquals(
            PlaceStep.CLICK,
            Eagle.placeDecision(78f, 78f, 6f, 0, 3, bridgeAssistEnabled = true),
            "BridgeAssist already holds the angle: cooperate and place"
        )
    }
}
