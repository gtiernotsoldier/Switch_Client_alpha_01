package io.doppel.adapter.common.module.world

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Category
import io.doppel.adapter.common.module.Module

/**
 * NoCameraBob — the crosshair stays where you are aiming while you walk.
 *
 * Vanilla's view bobbing sways the **camera**, and because the crosshair is pinned to the centre of
 * the screen it appears to slide across the world with every step: aiming while moving is a moving
 * target. Turning vanilla's "View Bobbing" off fixes exactly that — but it also takes away the
 * held-item sway, and that sway is the part that makes walking feel like walking.
 *
 * Those two turn out to be separate in 1.8.9. Disassembling the official client jar:
 * `ItemRenderer` reads **no GameSettings field at all** (the item bob is driven by the player's walk
 * distance), while the camera transform is gated on `GameSettings.viewBobbing`. So switching that one
 * setting off stops the camera sway and leaves the hand exactly as it was.
 *
 * The player's own preference is respected: whatever the setting was when the module went on is what
 * is restored when it goes off, so someone who already plays with bobbing off never gets it switched
 * on behind their back.
 *
 * The platform half is `EventBridge.suppressCameraBob` (Forge applies it every rendered frame, and
 * finds the setting without guessing any obfuscated name — see ForgeEventBridge).
 */
object NoCameraBob : Module("NoCameraBob", Category.UTILITY) {

    override fun onEnable() {
        EventBridge.suppressCameraBob = true
    }

    override fun onDisable() {
        EventBridge.suppressCameraBob = false
    }
}
