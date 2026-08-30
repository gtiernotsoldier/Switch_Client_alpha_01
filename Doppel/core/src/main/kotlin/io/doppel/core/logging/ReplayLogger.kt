package io.doppel.core.logging

import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.time.Instant

/**
 * Replay logger for JSON Lines (.jsonl) logging.
 *
 * Implements Constitution §2 (Debuggability):
 * "The adapter layer can record per-tick state, decisions, and network packets,
 *  facilitating post-kick analysis and review."
 *
 * Format: one JSON object per line, each with a timestamp, event type, and data payload.
 * Files are rotated by session (one file per client run).
 *
 * Usage:
 *   ReplayLogger.init(File("logs/replay"))
 *   ReplayLogger.log("velocity_decision", mapOf("mode" to "Legit", "reduced" to true))
 *   ReplayLogger.close()
 */
object ReplayLogger {

    private var writer: BufferedWriter? = null
    private var enabled: Boolean = false
    private var logDir: File? = null
    private var entryCount: Long = 0

    /** Maximum entries per file before rotation (0 = unlimited). */
    var maxEntriesPerFile: Long = 100_000

    // ========== Lifecycle ==========

    /**
     * Initialize the replay logger. Creates the log directory if needed
     * and opens a new session file.
     *
     * @param directory Directory to store .jsonl files.
     */
    fun init(directory: File) {
        close()
        logDir = directory
        directory.mkdirs()
        val sessionFile = File(directory, "replay_${System.currentTimeMillis()}.jsonl")
        writer = BufferedWriter(FileWriter(sessionFile, false))
        enabled = true
        entryCount = 0
        CoreLogger.info("[ReplayLogger] Session started: ${sessionFile.name}")
    }

    /**
     * Close the current session file.
     */
    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
        if (enabled) {
            CoreLogger.info("[ReplayLogger] Session closed ($entryCount entries)")
        }
        enabled = false
    }

    // ========== Logging ==========

    /**
     * Log an event with structured data.
     *
     * @param event Event type identifier (e.g. "velocity_decision", "aim_tick", "strategy_failure").
     * @param data Key-value payload. Values must be JSON-serializable primitives/strings.
     */
    fun log(event: String, data: Map<String, Any>) {
        if (!enabled) return
        val w = writer ?: return

        try {
            val json = buildJsonLine(event, data)
            w.write(json)
            w.newLine()
            entryCount++

            // Rotate if needed
            if (maxEntriesPerFile > 0 && entryCount >= maxEntriesPerFile) {
                rotate()
            }
        } catch (e: Exception) {
            // Logging must never crash the client
            CoreLogger.warn("[ReplayLogger] Write failed: ${e.message}")
        }
    }

    /**
     * Convenience: log a simple event with no payload.
     */
    fun log(event: String) {
        log(event, emptyMap())
    }

    /**
     * Flush buffered entries to disk.
     * Call periodically (e.g. every 100 ticks) to avoid data loss on crash.
     */
    fun flush() {
        try {
            writer?.flush()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ========== Status ==========

    fun isEnabled(): Boolean = enabled
    fun getEntryCount(): Long = entryCount

    // ========== Internal ==========

    private fun rotate() {
        val dir = logDir ?: return
        close()
        val newFile = File(dir, "replay_${System.currentTimeMillis()}.jsonl")
        writer = BufferedWriter(FileWriter(newFile, false))
        enabled = true
        entryCount = 0
        CoreLogger.debug("[ReplayLogger] Rotated to: ${newFile.name}")
    }

    /**
     * Build a JSON Lines entry manually (no external dependency in core).
     * Format: {"ts":"...","event":"...","data":{...}}
     */
    private fun buildJsonLine(event: String, data: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{\"ts\":\"").append(Instant.now().toString()).append('"')
        sb.append(",\"event\":\"").append(escapeJson(event)).append('"')
        sb.append(",\"data\":{")

        var first = true
        for ((key, value) in data) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJson(key)).append("\":")
            sb.append(formatValue(value))
        }

        sb.append("}}")
        return sb.toString()
    }

    private fun formatValue(value: Any): String = when (value) {
        is String -> "\"${escapeJson(value)}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> {
            val entries = value.entries.joinToString(",") { (k, v) ->
                "\"${escapeJson(k.toString())}\":${formatValue(v ?: "null")}"
            }
            "{$entries}"
        }
        is List<*> -> "[${value.joinToString(",") { formatValue(it ?: "null") }}]"
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
