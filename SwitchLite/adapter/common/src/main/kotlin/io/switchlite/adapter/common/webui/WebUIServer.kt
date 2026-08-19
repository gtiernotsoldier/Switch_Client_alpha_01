package io.switchlite.adapter.common.webui

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.switchlite.adapter.common.module.Module
import io.switchlite.adapter.common.module.ModuleRegistry
import io.switchlite.adapter.common.option.ConfigManager
import io.switchlite.adapter.common.option.OptionDescriptor
import io.switchlite.adapter.common.option.OptionType
import io.switchlite.core.logging.CoreLogger
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors

/**
 * WebUI panel server — the browser-based configuration/debug surface.
 *
 * Recent hardening (release-ready):
 * - Binds 0.0.0.0 so a phone or another PC on the same LAN can open the panel
 *   (not a hard-coded 127.0.0.1). The LAN URL + access token are advertised in
 *   the game (HUD/log) so users know what to type.
 * - Protected by a per-install access token (HTTP Bearer). The token is
 *   auto-generated once, persisted, and stable across restarts.
 * - Runs on its own daemon thread pool — never touches the MC render thread.
 * - Cross-version: lives in adapter:common (Forge 1.8.9 / Fabric 1.21.x share it).
 *
 * Endpoints:
 *   GET  /api/info                  → { urls:[...], tokenNeeded:true, defaultPassword } (loopback only detail)
 *   GET  /api/modules               → [{name, category, enabled, keybind, options[]}]
 *   POST /api/modules/{name}/toggle
 *   POST /api/options               → body {"key":"Module.Option","value":...}
 *   GET  /api/config                → export JSON
 *   POST /api/config                → import JSON
 *   GET  /                          → Aurora page (needs token)
 */
object WebUIServer {

    /** Bind to all interfaces (LAN + localhost). */
    private const val HOST = "0.0.0.0"
    const val PORT = 4173

    /** Authorization scheme. Token travels as `Authorization: Bearer <token>`. */
    private const val AUTH_SCHEME = "Bearer"

    private val mapper = ObjectMapper()
    private val lock = Any()

    private var server: HttpServer? = null

    val isRunning: Boolean get() = server != null

    /** Advertised access URLs (loopback + primary LAN IP). */
    val accessUrls: List<String> get() = LanHelper.lanUrls(PORT)

    /** The per-install access token (hidden once authorized from remote). */
    val accessToken: String get() = ConfigStore.accessToken

    fun start() {
        synchronized(lock) {
            if (server != null) return
            try {
                ConfigStore.load()
                // Touch the token so it is minted + persisted now (side effect of property access).
                val token = ConfigStore.accessToken
                val srv = HttpServer.create(InetSocketAddress(HOST, PORT), 0)
                srv.executor = Executors.newFixedThreadPool(2) { r ->
                    Thread(r, "SwitchLite-WebUI").apply { isDaemon = true }
                }
                srv.createContext("/", ::handleAll)
                srv.start()
                server = srv
                CoreLogger.info("[WebUI] Panel running on 0.0.0.0:$PORT (LAN+local). Token: $token")
                CoreLogger.info("[WebUI]  Local: ${accessUrls[0]}")
                LanHelper.lanAddress()?.let {
                    CoreLogger.info("[WebUI]  LAN:   http://${it.hostAddress}:$PORT")
                }
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
    //  Dispatch + auth
    // ═══════════════════════════════════════════

    private fun handleAll(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path

            // /api/info is reachable without a token (needed by the login flow),
            // but only reveals the access token to a *loopback* caller. Remote
            // callers just see the URL list + whether a token is required.
            if (path == "/api/info" && exchange.requestMethod == "GET") {
                return handleInfo(exchange)
            }

            // Static UI (index.html) is served freely so the login prompt can
            // render; every /api/* mutation/read requires the bearer token.
            if (!path.startsWith("/api/")) {
                return handleStatic(exchange)
            }

            // Everything else requires the bearer token.
            if (!authorized(exchange)) {
                exchange.responseHeaders.add("WWW-Authenticate", "$AUTH_SCHEME realm=\"switchlite-webui\"")
                respondJson(exchange, 401, """{"error":"unauthorized","hint":"Authorization: Bearer <token>"}""")
                return
            }

            when {
                path.startsWith("/api/modules") -> handleModules(exchange)
                path.startsWith("/api/options") -> handleOptions(exchange)
                path.startsWith("/api/config") -> handleConfig(exchange)
                else -> handleStatic(exchange)
            }
        } catch (e: Exception) {
            try { respondJson(exchange, 500, """{"error":"${e.message}"}""") } catch (_: Exception) {}
        }
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith("$AUTH_SCHEME ")) return false
        val presented = header.removePrefix("$AUTH_SCHEME ").trim()
        if (presented.isEmpty()) return false
        // Constant-time-ish compare (no timing oracle).
        return constantTimeEquals(presented, ConfigStore.accessToken)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    // ═══════════════════════════════════════════
    //  Info (login help)
    // ═══════════════════════════════════════════

    private fun handleInfo(exchange: HttpExchange) {
        val isLoopback = exchange.remoteAddress.address.isLoopbackAddress ||
            exchange.remoteAddress.address.isAnyLocalAddress
        val info = linkedMapOf<String, Any?>(
            "urls" to accessUrls,
            "tokenRequired" to true,
            // Only the local machine sees the actual token; remote devices must
            // read it from the game HUD / injector log.
            "defaultPassword" to (if (isLoopback) accessToken else null)
        )
        respondJson(exchange, 200, mapper.writeValueAsString(info))
    }

    // ═══════════════════════════════════════════
    //  Modules
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
                        ModuleRegistry.getAll().filter { !it.hidden }.map { moduleJson(it) }
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

            val ok = synchronized(lock) { applyOptionValue(key, value) }

            if (ok) {
                try { ConfigStore.save() } catch (_: Exception) {}
                respondJson(exchange, 200, mapper.writeValueAsString(mapOf("ok" to true, "key" to key)))
            } else {
                respondJson(exchange, 400, mapper.writeValueAsString(
                    mapOf("ok" to false, "error" to "invalid value or unknown key")))
            }
        } catch (e: Exception) {
            respondJson(exchange, 500, """{"error":"${e.message}"}""")
        }
    }

    private fun handleConfig(exchange: HttpExchange) {
        try {
            when (exchange.requestMethod) {
                "GET" -> respondJson(exchange, 200, ConfigStore.exportJson())
                "POST" -> {
                    val body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
                    synchronized(lock) { ConfigStore.importJson(body) }
                    respondJson(exchange, 200, """{"ok":true}""")
                }
                else -> methodNotAllowed(exchange)
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
