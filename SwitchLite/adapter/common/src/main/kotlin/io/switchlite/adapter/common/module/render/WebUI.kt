package io.switchlite.adapter.common.module.render

import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.webui.WebUIServer

/**
 * WebUI module — browser-based configuration/debug panel.
 *
 * The in-game client keeps only the HUD (and future in-game modules like
 * wallhack/ESP). All parameter tuning + module toggling happens in the
 * browser against the local WebUIServer (Aurora visual system).
 *
 * The server runs on its own daemon thread pool — zero impact on the MC
 * render thread. Enable this module to start it (default: on).
 */
object WebUI : Module("WebUI", Category.RENDER) {

    init {
        showRedIndicator = false
        hidden = true
    }

    override fun onEnable() {
        WebUIServer.start()
    }

    override fun onDisable() {
        WebUIServer.stop()
    }
}
