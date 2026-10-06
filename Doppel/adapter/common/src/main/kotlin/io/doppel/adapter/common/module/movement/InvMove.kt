package io.doppel.adapter.common.module.movement

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.option.boolean
import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState

/**
 * InvMove (背包行走) — keep walking while a screen (inventory, chest, chat, …) is open.
 *
 * Why the player stops in vanilla: opening a screen calls `KeyBinding.unPressAllKeys()`, and while a
 * non-chat screen is open `Minecraft.runTick()` skips its whole keyboard loop, so the KeyBindings the
 * movement input is built from stay down. The player's own fingers never arrive. (Chat is the one
 * screen that keeps the loop running, which is why typing must never be disturbed.)
 *
 * The fix is Raven's: re-apply the *key state* the game would have seen, so
 * `MovementInputFromOptions.updatePlayerMoveState()` — called from `EntityPlayerSP` on the next
 * tick — rebuilds the same movement input as without the screen. Two properties matter:
 *
 *  1. Only state is written, never a key event. Nothing here consumes or cancels a keypress, so a
 *     screen that owns the keyboard still receives it (typing "w" in chat still types "w").
 *  2. The module does not touch Minecraft at all. It publishes intent on [EventBridge] and the
 *     platform applies it on the MC main thread each frame
 *     (`ForgeEventBridge.applyGuiMovement()`), which is where KeyBinding writes and the sprint
 *     re-assert belong.
 *
 * Options (mirroring the reference `InvMove.java`):
 *  - **KeepSprint** — keep sprinting while a screen is open (Raven's `allowSprinting`). Off forces
 *    sprint off for as long as a screen is open, because a re-applied move input would otherwise let
 *    vanilla's own rule hold the sprint state.
 *  - **OnlyInventory** — re-apply only for container screens (`GuiContainer`: inventory, chest,
 *    crafting, furnace, …), never for chat. This is Raven's chat exclusion as a switch.
 *
 * Not ported from the reference: its Blink/Close inventory modes and its "Modify motion after click"
 * / motion-scaling sliders. Those are inventory-click packet games (they blink `C0EPacketClickWindow`
 * and re-send it on close) that need the packet interceptor, not the movement path — a different
 * module's worth of work, not part of "walk while the inventory is open".
 */
object InvMove : Module("InvMove", Category.MOVEMENT) {

    /** Keep sprinting (and the sprint key) alive while a screen is open. */
    private val keepSprint by boolean("KeepSprint", true)

    /** Only container screens (inventory/chest/…) keep walking; chat and other screens do not. */
    private val onlyInventory by boolean("OnlyInventory", false)

    /**
     * Mirror the options into the platform contract every tick. The WebUI can flip an option at any
     * time, and the platform reads the flags per frame, so a one-off publish on enable would keep the
     * old value until the module was re-enabled.
     */
    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) publish()
    }

    override fun onEnable() {
        publish()
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
        // Every flag back to its inert default: a module that is off must not keep re-applying keys
        // (the player would walk with a screen open and no module to explain it).
        EventBridge.keepMovementInGui = false
        EventBridge.guiMovementKeepSprint = false
        EventBridge.guiMovementOnlyInventory = false
    }

    private fun publish() {
        EventBridge.keepMovementInGui = true
        EventBridge.guiMovementKeepSprint = keepSprint
        EventBridge.guiMovementOnlyInventory = onlyInventory
    }
}

/**
 * The decisions InvMove makes, with no Minecraft in them — unit-tested.
 *
 * They live outside [InvMove] so the platform (which has the screen object and the player) and the
 * tests make the *same* call. Keep them free of state: every input is a parameter.
 */
object InvMoveRules {

    /** Vanilla's sprint food gate (`EntityPlayerSP`: `foodLevel > 6 || allowFlying`). */
    const val SPRINT_MIN_FOOD = 6

    /**
     * Whether the platform should re-apply the movement keys on this frame.
     *
     * @param keepMovementInGui the module's contract flag (false = no module wants it)
     * @param screenOpen whether `mc.currentScreen != null`
     * @param onlyInventory the "OnlyInventory" option
     * @param containerScreen whether the open screen is a `GuiContainer` (or subclass)
     */
    fun shouldReapplyMovement(
        keepMovementInGui: Boolean,
        screenOpen: Boolean,
        onlyInventory: Boolean,
        containerScreen: Boolean
    ): Boolean {
        if (!keepMovementInGui || !screenOpen) return false
        if (onlyInventory && !containerScreen) return false
        return true
    }

    /**
     * Whether to re-assert sprint while a screen is open (Raven: `allowSprinting` +
     * `moveForward >= 0.8 && foodMet && !isSprinting`).
     *
     * `alreadySprinting` is checked because `setSprinting(true)` on every frame would re-apply the
     * sprint speed modifier (and reset the 600-tick sprint window) pointlessly.
     */
    fun shouldKeepSprinting(
        keepSprint: Boolean,
        alreadySprinting: Boolean,
        forwardActive: Boolean,
        foodLevel: Int,
        allowFlying: Boolean
    ): Boolean {
        if (!keepSprint || alreadySprinting || !forwardActive) return false
        return foodLevel > SPRINT_MIN_FOOD || allowFlying
    }

    /** Whether sprint must be dropped while a screen is open (KeepSprint = off). */
    fun shouldSuppressSprinting(keepSprint: Boolean, alreadySprinting: Boolean): Boolean =
        !keepSprint && alreadySprinting
}
