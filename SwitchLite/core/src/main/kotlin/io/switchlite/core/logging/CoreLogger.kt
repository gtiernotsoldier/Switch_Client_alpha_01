package io.switchlite.core.logging

/**
 * Lightweight logger for the core layer.
 * Zero external dependencies — prints to stdout with level prefix.
 * In production, the adapter layer can replace this with SLF4J or platform logging.
 */
object CoreLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    var minLevel: Level = Level.INFO

    /**
     * File sink — mirrors every log line to %TEMP%\switchlite-agent.log so
     * render/module diagnostics are visible even when MC's stdout is swallowed
     * (javaw.exe). Same file as Agent.log; both append, interleaving is fine.
     */
    private val fileSink: java.io.PrintWriter? by lazy {
        try {
            val tmp = System.getProperty("java.io.tmpdir") ?: return@lazy null
            java.io.PrintWriter(java.io.FileWriter(java.io.File(tmp, "switchlite-agent.log"), true), true)
        } catch (_: Exception) { null }
    }

    fun debug(msg: String) = log(Level.DEBUG, msg)
    fun info(msg: String) = log(Level.INFO, msg)
    fun warn(msg: String) = log(Level.WARN, msg)
    fun error(msg: String) = log(Level.ERROR, msg)

    private fun log(level: Level, msg: String) {
        if (level.ordinal < minLevel.ordinal) return
        val tag = when (level) {
            Level.DEBUG -> "[DEBUG]"
            Level.INFO -> "[INFO]"
            Level.WARN -> "[WARN]"
            Level.ERROR -> "[ERROR]"
        }
        val line = "$tag [SwitchLite] $msg"
        println(line)
        try {
            fileSink?.println("[" + java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date()) + "] " + line)
            fileSink?.flush()
        } catch (_: Exception) {}
    }
}
