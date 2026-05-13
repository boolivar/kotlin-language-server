package org.javacs.kt

import org.tinylog.core.LogEntry
import org.tinylog.jul.JulTinylogBridge
import org.tinylog.writers.AbstractFormatPatternWriter
import kotlin.concurrent.Volatile

typealias LOG = org.tinylog.kotlin.Logger
typealias LogLevel = org.tinylog.Level

class LogMessage(val level: LogLevel, val message: String)

object BackendWriter: AbstractFormatPatternWriter(mapOf()) {

    @Volatile
    var level = LogLevel.TRACE

    var backend: ((LogMessage) -> Unit)? = null
        set(value) {
            field = value
            while (preliminaryQueue.isNotEmpty()) {
                write(preliminaryQueue.removeFirst())
            }
        }

    val preliminaryQueue = ArrayDeque<LogEntry>()

    override fun write(logEntry: LogEntry) {
        if (logEntry.level >= level) {
            backend?.invoke(LogMessage(logEntry.level, render(logEntry))) ?: preliminaryQueue.add(logEntry)
        }
    }

    override fun flush() {}

    override fun close() {}
}

fun LOG.connectJULFrontend() = JulTinylogBridge.activate()

fun LOG.connectOutputBackend(backend: (LogMessage) -> Unit) {
    BackendWriter.backend = backend
}

fun LOG.connectErrorBackend(backend: (LogMessage) -> Unit) {}

fun LOG.connectStdioBackend() {}
