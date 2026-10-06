package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category

/**
 * NoHurtCam — no camera shake when taking damage.
 *
 * The shake is a *camera transform*: `EntityRenderer.hurtCameraEffect(partialTicks)` rolls the view
 * by `-sin(f^4 * PI) * 14°`, where `f = (hurtTime - partialTicks) / maxHurtTime`. It is not entity
 * state, so nothing here may touch `hurtTime`:
 *
 *  - `hurtTime > 0` is how HitSelect and Velocity know the player was just hit — zeroing it on the
 *    tick (what this module used to do through `EventBridge.resetHurtCam()`) silently broke both.
 *  - `hurtTime` also drives the vanilla red damage tint, which the old behaviour removed as well.
 *
 * So the module only publishes intent ([EventBridge.suppressHurtCam]) and the platform cancels the
 * tilt on the render frame, leaving `hurtTime` (and therefore the hit signal and the tint) intact.
 * Off = flag false = the platform writes nothing and any value it did write is restored, so a
 * disabled module can never leave hurt-cam state behind.
 */
object NoHurtCam : Module("NoHurtCam", Category.VISUAL) {

    override fun onEnable() {
        EventBridge.suppressHurtCam = true
    }

    override fun onDisable() {
        // Clearing the flag is what makes the platform restore the vanilla value — see
        // ForgeEventBridge.applyFrameOverrides(). Nothing is left latched for the next enable.
        EventBridge.suppressHurtCam = false
    }
}
