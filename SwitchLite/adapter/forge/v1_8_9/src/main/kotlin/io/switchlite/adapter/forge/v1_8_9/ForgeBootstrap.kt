package io.switchlite.adapter.forge.v1_8_9

/**
 * Forge bootstrap entry point.
 * Registers events and bridges to common/module.
 */
object ForgeBootstrap {
    fun init() {
        // TODO: Initialize Forge bootstrap
        ForgeEventBridge.registerListeners()
    }
}
