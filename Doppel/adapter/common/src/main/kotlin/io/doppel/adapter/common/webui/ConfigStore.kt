package io.doppel.adapter.common.webui

import io.doppel.adapter.common.option.ConfigManager
import io.doppel.core.logging.CoreLogger
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

    private const val FILE_NAME = "doppel-config.json"

    /** Key under which the access token is persisted inside the config JSON. */
    private const val TOKEN_KEY = "WebUI.accessToken"

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

    /**
     * Access token protecting the WebUI panel when bound to any interface
     * (LAN / 0.0.0.0). Auto-generated on first use and kept stable across
     * restarts so the user's browser stays authorized. Persisted inside the
     * config JSON under [TOKEN_KEY].
     */
    val accessToken: String by lazy {
        val existing = tokenFromDisk()
        if (existing != null) existing else generateAndPersist()
    }

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

    // ── Token persistence (kept apart from module-backed options) ──

    private fun tokenFromDisk(): String? {
        return try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            if (!configFile.exists()) return null
            val map = mapper.readValue(configFile, Map::class.java) as Map<String, Any?>
            map[TOKEN_KEY] as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun generateAndPersist(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        try {
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val map = configFile.takeIf { it.exists() }
                ?.let { runCatching { mapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
                ?: emptyMap()
            val merged = LinkedHashMap<String, Any?>()
            merged.putAll(map)
            merged[TOKEN_KEY] = token
            configFile.parentFile?.mkdirs()
            mapper.writeValue(configFile, merged)
        } catch (e: Exception) {
            CoreLogger.warn("[ConfigStore] Could not persist access token: ${e.message}")
        }
        return token
    }
}
