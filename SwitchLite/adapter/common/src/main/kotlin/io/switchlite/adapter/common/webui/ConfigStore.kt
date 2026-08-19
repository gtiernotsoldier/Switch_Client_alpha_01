package io.switchlite.adapter.common.webui

import io.switchlite.adapter.common.option.ConfigManager
import io.switchlite.core.logging.CoreLogger
import java.io.File

/**
 * Persistence for configuration set through the WebUI panel.
 *
 * Lives in adapter:common (cross-version / future mobile). The config file
 * sits under the working directory or %TEMP% so an injected client can write
 * it without special permissions. Values are validated by ConfigManager on
 * load (unknown/invalid entries are skipped), so a hand-edited or stale file
 * can never corrupt runtime state.
 *
 * This is called from the WebUI server's own daemon thread — never from the
 * MC render thread.
 */
object ConfigStore {

    private const val FILE_NAME = "switchlite-config.json"

    private val configFile: File by lazy {
        val wd = File(System.getProperty("user.dir"))
        val inWorkdir = File(wd, FILE_NAME)
        if (inWorkdir.parentFile != null && wd.canWrite()) {
            inWorkdir
        } else {
            val tmp = System.getProperty("java.io.tmpdir")
            File(tmp, FILE_NAME)
        }
    }

    val path: String get() = configFile.absolutePath

    /** Load persisted config (no-op if absent). Called once on WebUI start. */
    fun load() {
        ConfigManager.load(configFile)
        CoreLogger.info("[ConfigStore] Config file: ${configFile.absolutePath}")
    }

    /** Persist the current configuration to disk. */
    fun save() {
        ConfigManager.save(configFile)
    }

    /** Export the full current config as a JSON string. */
    fun exportJson(): String = try { ConfigManager.toJson() } catch (e: Exception) { "{}" }

    /** Import a full config JSON string (validated by ConfigManager). */
    fun importJson(json: String) {
        ConfigManager.loadFromString(json)
        save()
    }
}
