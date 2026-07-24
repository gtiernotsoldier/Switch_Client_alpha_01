package io.switchlite.core.logging

/**
 * Lightweight logger for the core layer.
 * Zero external dependencies — prints to stdout with level prefix.
 * In production, the adapter layer can replace this with SLF4J or platform logging.
 */
object CoreLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    var minLevel: Level = Level.INFO

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
        println("$tag [SwitchLite] $msg")
    }
}
