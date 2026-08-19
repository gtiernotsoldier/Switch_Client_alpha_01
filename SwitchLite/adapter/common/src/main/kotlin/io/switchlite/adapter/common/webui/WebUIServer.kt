package io.switchlite.adapter.common.webui

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.switchlite.adapter.common.module.Category
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.option.ConfigManager
import io.switchlite.adapter.common.option.OptionDescriptor
import io.switchlite.adapter.common.option.OptionType
import io.switchlite.core.logging.CoreLogger
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * WebUI panel server — the browser-based configuration/debug surface.
 *
 * Architecture (per the project direction):
 * - The in-game client renders ONLY the HUD (and future in-game modules like
 *   wallhack/ESP). All parameter tuning + module toggling happens HERE, in a
 *   browser, against a local HTTP API.
 * - The server runs on its own daemon executor — it NEVER touches the MC
 *   render thread or the agent tick thread's hot path. Idle cost is zero.
 * - Cross-version: this lives in adapter:common, so every platform (Forge
 *   1.8.9, Fabric 1.21.x) gets the same panel for free. Mobile access comes
 *   later (bind to LAN + auth).
 *
 * Endpoints:
 *   GET  /                        → the Aurora visual system page (index.html)
 *   GET  /api/modules             → [{name, category, enabled, keybind, options[]}]
 *   POST /api/modules/{name}/toggle
 *   POST /api/options             → body {"key":"Module.Option","value":...}
 */
object WebUIServer {

    /** Loopback only — local debugging panel. Mobile/LAN support comes later. */
    private const val HOST = "127.0.0.1"
    const val PORT = 4173

    private val mapper = ObjectMapper()
    private val lock = Any()

    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        synchronized(lock) {
            if (server != null) return
            try {
                val srv = HttpServer.create(InetSocketAddress(HOST, PORT), 0)
                srv.executor = Executors.newFixedThreadPool(2) { r ->
                    Thread(r, "SwitchLite-WebUI").apply { isDaemon = true }
                }
                srv.createContext("/api/modules", ::handleModules)
                srv.createContext("/api/options", ::handleOptions)
                srv.createContext("/", ::handleStatic)
                srv.start()
                server = srv
                CoreLogger.info("[WebUI] Panel running at http://$HOST:$PORT (modules + options API)")
            } catch (e: Exception) {
                CoreLogger.error("[WebUI] Failed to start on $HOST:$PORT: ${e.javaClass.simpleName}: ${e.message}")
                server = null
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            server?.stop(0)
            server = null
            CoreLogger.info("[WebUI] Panel stopped")
        }
    }

    // ═══════════════════════════════════════════
    //  Handlers
    // ═══════════════════════════════════════════

    private fun handleModules(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val name = path.removePrefix("/api/modules/")
                .removeSuffix("/toggle")
                .let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

            when {
                path.endsWith("/toggle") && name.isNotBlank() -> {
                    if (exchange.requestMethod != "POST") return methodNotAllowed(exchange)
                    synchronized(lock) { ModuleRegistry.toggle(name) }
                    val mod = ModuleRegistry.get(name)
                    respondJson(exchange, 200, mapper.writeValueAsString(
                        mapOf("name" to name, "enabled" to (mod?.enabled ?: false))
                    ))
                }
                exchange.requestMethod == "GET" -> {
                    val list = synchronized(lock) {
                        ModuleRegistry.getAll()
                            .filter { !it.hidden }
                            .map { moduleJson(it) }
                    }
                    respondJson(exchange, 200, mapper.writeValueAsString(list))
                }
                else -> methodNotAllowed(exchange)
            }
        } catch (e: Exception) {
            respondJson(exchange, 500, """{"error":"${e.message}"}""")
        }
    }

    private fun handleOptions(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") return methodNotAllowed(exchange)
            val body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            val req = mapper.readValue(body, Map::class.java) as Map<String, Any?>
            val key = req["key"] as? String ?: return respondJson(exchange, 400, """{"error":"missing key"}""")
            val value = req["value"]

            val ok = synchronized(lock) {
                applyOptionValue(key, value)
            }

            if (ok == true) {
                respondJson(exchange, 200, """{"ok":true,"key":"$key"}""")
            } else {
                respondJson(exchange, 400, """{"ok":false,"error":"invalid value or unknown key"}""")
            }
        } catch (e: Exception) {
            respondJson(exchange, 500, """{"error":"${e.message}"}""")
        }
    }

    private fun handleStatic(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val resource = when {
                path == "/" || path.isEmpty() -> "/switchlite/webui/index.html"
                else -> "/switchlite/webui${if (path == "/") "/index.html" else path}"
            }
            val stream = WebUIServer::class.java.getResourceAsStream(resource)
            if (stream == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return
            }
            val bytes = stream.use { it.readBytes() }
            val type = when {
                resource.endsWith(".html") -> "text/html; charset=utf-8"
                resource.endsWith(".css") -> "text/css; charset=utf-8"
                resource.endsWith(".js") -> "application/javascript; charset=utf-8"
                else -> "application/octet-stream"
            }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            try { exchange.sendResponseHeaders(500, -1) } catch (_: Exception) {}
            exchange.close()
        }
    }

    // ═══════════════════════════════════════════
    //  JSON builders
    // ═══════════════════════════════════════════

    /** Apply a raw JSON value to a registered option, coerced by its type. */
    private fun applyOptionValue(key: String, value: Any?): Boolean {
        val meta = ConfigManager.getMeta(key) ?: return false
        return try {
            when (meta.type) {
                OptionType.FLOAT -> { ConfigManager.set(key, (value as Number).toFloat()); true }
                OptionType.INT, OptionType.PROBABILITY -> { ConfigManager.set(key, (value as Number).toInt()); true }
                OptionType.BOOLEAN -> { ConfigManager.set(key, value as Boolean); true }
                OptionType.CHOICES, OptionType.STRING -> { ConfigManager.set(key, value as String); true }
                OptionType.ENUM -> {
                    val enumCls = (meta.default as Enum<*>).javaClass
                    val constant = enumCls.enumConstants?.firstOrNull { (it as Enum<*>).name == value }
                    if (constant != null) { ConfigManager.set(key, constant); true } else false
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun moduleJson(module: Module): Map<String, Any?> {
        val options = ConfigManager.optionsOf(module.name).map { optionJson(it) }
        return linkedMapOf(
            "name" to module.name,
            "category" to module.category.name,
            "enabled" to module.enabled,
            "keybind" to module.keybind,
            "options" to options
        )
    }

    private fun optionJson(desc: OptionDescriptor): Map<String, Any?> {
        val meta = desc.meta
        val current = ConfigManager.currentValue(desc.key)
        return linkedMapOf(
            "key" to desc.key,
            "name" to desc.name,
            "type" to meta.type.name,
            "value" to current,
            "min" to when (meta.type) {
                OptionType.FLOAT -> meta.rangeMin
                OptionType.INT, OptionType.PROBABILITY -> meta.intRangeMin
                else -> null
            },
            "max" to when (meta.type) {
                OptionType.FLOAT -> meta.rangeMax
                OptionType.INT, OptionType.PROBABILITY -> meta.intRangeMax
                else -> null
            },
            "unit" to meta.unit,
            "choices" to meta.displayChoices()
        )
    }

    // ═══════════════════════════════════════════
    //  Response helpers
    // ═══════════════════════════════════════════

    private fun respondJson(exchange: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun methodNotAllowed(exchange: HttpExchange) {
        exchange.sendResponseHeaders(405, -1)
        exchange.close()
    }
}
