package io.doppel.adapter.common.module.render

import io.doppel.adapter.common.api.EventBridge
import io.doppel.adapter.common.module.Module
import io.doppel.adapter.common.module.Category

/**
 * Fullbright — maximum brightness for dark environments.
 *
 * Sets the game's gamma to 100.0 on enable, restoring the value on disable.
 * No configuration. Works on 1.8 and 1.9+.
 */
object Fullbright : Module("Fullbright", Category.RENDER) {

    private var savedGamma: Float = 1.0f

    override fun onEnable() {
        savedGamma = 1.0f
        EventBridge.setGamma(100.0f)
    }

    override fun onDisable() {
        EventBridge.setGamma(savedGamma)
    }
}
