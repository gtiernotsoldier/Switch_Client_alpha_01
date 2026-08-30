package io.doppel.adapter.common.module.render

import io.doppel.core.model.PlayerState
import io.doppel.core.model.TargetState
import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category

/**
 * NoHurtCam — disables the camera shake when taking damage.
 *
 * Resets player hurtTime to 0 every tick, preventing the vanilla
 * camera tilt effect on hit. No configuration.
 */
object NoHurtCam : Module("NoHurtCam", Category.RENDER) {

    private val tickListener: (PlayerState, TargetState?) -> Unit = { _, _ ->
        if (enabled) EventBridge.resetHurtCam()
    }

    override fun onEnable() {
        EventBridge.registerTickListener(tickListener)
    }

    override fun onDisable() {
        EventBridge.unregisterTickListener(tickListener)
    }
}
