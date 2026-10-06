package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category

/**
 * NoFOV — no dynamic FOV from sprinting, speed potions or flight.
 *
 * The FOV the renderer uses is `fovSetting * fovModifierHand` (interpolated with its prev value);
 * `EntityRenderer.updateFovModifierHand()` rebuilds that modifier from the player's current
 * sprint/speed multiplier **inside `Minecraft.runTick()`**, i.e. between two rendered frames. A
 * 20 Hz write from the module tick therefore either lost the race (the renderer recomputed after it)
 * or was immediately smoothed halfway back — which is why the old
 * `EventBridge.resetFovModifier()`-on-tick approach looked like it "did not work".
 *
 * This module now only publishes intent ([EventBridge.lockFovModifier]); the platform holds the FOV
 * on the render frame (see ForgeEventBridge.applyFrameOverrides, which also neutralises the value
 * the per-tick recompute reads, so the FOV cannot step back one frame per tick). Off = flag false =
 * the platform stops writing and restores what it changed, so the FOV can never stay stuck.
 */
object NoFOV : Module("NoFOV", Category.VISUAL) {

    override fun onEnable() {
        EventBridge.lockFovModifier = true
    }

    override fun onDisable() {
        EventBridge.lockFovModifier = false
    }
}
