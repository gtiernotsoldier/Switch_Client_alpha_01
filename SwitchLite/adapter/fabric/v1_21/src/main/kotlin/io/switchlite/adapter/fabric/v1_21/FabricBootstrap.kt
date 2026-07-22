package io.switchlite.adapter.fabric.v1_21

/**
 * Fabric bootstrap entry point.
 * Registers events and bridges to common/module.
 */
object FabricBootstrap {
    fun init() {
        // TODO: Initialize Fabric bootstrap
        FabricEventBridge.registerListeners()
    }
}
