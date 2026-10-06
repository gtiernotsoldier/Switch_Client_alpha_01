package io.doppel.adapter.common.module.movement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * InvMove's decisions, which the Forge adapter makes per frame (`ForgeEventBridge.applyGuiMovement`).
 *
 * They are separated from the module so the platform and these tests take the same branch: the
 * platform only supplies what it can see (is a screen open, is it a container screen, is the player
 * moving forward, …) and never re-implements the rules.
 */
class InvMoveRulesTest {

    // ── shouldReapplyMovement: when the platform re-presses the movement keys ──

    @Test
    fun `no module asking means no re-application`() {
        assertFalse(
            InvMoveRules.shouldReapplyMovement(
                keepMovementInGui = false, screenOpen = true,
                onlyInventory = false, containerScreen = true
            ),
            "with the contract flag clear the platform must not touch a single KeyBinding"
        )
    }

    @Test
    fun `no screen open means no re-application`() {
        // In-world the game's own input loop owns the keys; re-pressing them here would fight it.
        assertFalse(
            InvMoveRules.shouldReapplyMovement(
                keepMovementInGui = true, screenOpen = false,
                onlyInventory = false, containerScreen = false
            )
        )
    }

    @Test
    fun `a screen keeps the player walking, chat included`() {
        assertTrue(
            InvMoveRules.shouldReapplyMovement(
                keepMovementInGui = true, screenOpen = true,
                onlyInventory = false, containerScreen = false // chat is not a container screen
            )
        )
    }

    @Test
    fun `OnlyInventory keeps container screens and lets chat alone`() {
        assertTrue(
            InvMoveRules.shouldReapplyMovement(
                keepMovementInGui = true, screenOpen = true,
                onlyInventory = true, containerScreen = true
            ),
            "inventory/chest/crafting keep walking"
        )
        assertFalse(
            InvMoveRules.shouldReapplyMovement(
                keepMovementInGui = true, screenOpen = true,
                onlyInventory = true, containerScreen = false
            ),
            "chat and other screens keep their input to themselves (Raven skips GuiChat)"
        )
    }

    // ── shouldKeepSprinting: Raven's allowSprinting condition ──

    @Test
    fun `Keep sprint off never re-asserts sprint`() {
        assertFalse(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = false, alreadySprinting = false,
                forwardActive = true, foodLevel = 20, allowFlying = false
            )
        )
    }

    @Test
    fun `sprint is re-asserted only while moving forward on a full enough hunger bar`() {
        assertTrue(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = false,
                forwardActive = true, foodLevel = 20, allowFlying = false
            )
        )
        assertFalse(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = false,
                forwardActive = false, foodLevel = 20, allowFlying = false
            ),
            "standing still must not be turned into a sprint"
        )
        assertFalse(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = false,
                forwardActive = true, foodLevel = InvMoveRules.SPRINT_MIN_FOOD, allowFlying = false
            ),
            "vanilla's rule is foodLevel > 6, not >= 6"
        )
        assertTrue(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = false,
                forwardActive = true, foodLevel = 7, allowFlying = false
            )
        )
    }

    @Test
    fun `flying ignores the hunger gate, and an active sprint is left alone`() {
        assertTrue(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = false,
                forwardActive = true, foodLevel = 0, allowFlying = true
            ),
            "vanilla: foodLevel > 6 || capabilities.allowFlying"
        )
        assertFalse(
            InvMoveRules.shouldKeepSprinting(
                keepSprint = true, alreadySprinting = true,
                forwardActive = true, foodLevel = 20, allowFlying = false
            ),
            "re-asserting every frame would re-apply the sprint modifier pointlessly"
        )
    }

    // ── shouldSuppressSprinting: the Keep sprint = off branch ──

    @Test
    fun `Keep sprint off drops an active sprint while a screen is open`() {
        // A re-applied move input would keep vanilla's own sprint rule satisfied, so the state has to
        // be forced off instead of just releasing the key.
        assertTrue(InvMoveRules.shouldSuppressSprinting(keepSprint = false, alreadySprinting = true))
        assertFalse(InvMoveRules.shouldSuppressSprinting(keepSprint = false, alreadySprinting = false))
        assertFalse(InvMoveRules.shouldSuppressSprinting(keepSprint = true, alreadySprinting = true))
    }
}
