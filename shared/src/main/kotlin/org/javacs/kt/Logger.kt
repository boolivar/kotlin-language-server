package org.javacs.kt

import org.tinylog.core.LogEntry
import org.tinylog.core.TinylogLoggingProvider
import org.tinylog.jul.JulTinylogBridge
import org.tinylog.provider.ProviderRegistry
import org.tinylog.writers.AbstractFormatPatternWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.read
import kotlin.concurrent.write

typealias LOG = org.tinylog.kotlin.Logger

typealias LogLevel = org.tinylog.Level

class LogMessage(val level: LogLevel, val message: String)

class BackendWriter(props: Map<String, String>): AbstractFormatPatternWriter(props) {

    private val queue = ConcurrentLinkedQueue<LogMessage>()

    private val rwLock = ReentrantReadWriteLock()

    private var backend: (LogMessage) -> Unit = queue::add

    @Volatile
    var level = LogLevel.TRACE

    fun connect(backend: (LogMessage) -> Unit) = rwLock.write {
        while (queue.isNotEmpty()) {
            backend.invoke(queue.poll())
        }
        this.backend = backend
    }

    fun disconnect() = rwLock.write {
        this.backend = queue::add
    }

    override fun write(logEntry: LogEntry) {
        if (logEntry.level >= level) {
            rwLock.read {
                backend.invoke(LogMessage(logEntry.level, render(logEntry)))
            }
        }
    }

    override fun flush() { /* noop */ }

    override fun close() = disconnect()
}

fun LOG.connectJULFrontend() = JulTinylogBridge.activate()

fun LOG.connectOutputBackend(backend: (LogMessage) -> Unit) = LOG.connectBackend(backend)

fun LOG.connectErrorBackend(backend: (LogMessage) -> Unit) = LOG.connectBackend(backend)

fun LOG.connectStdioBackend() { /* noop */ }

fun LOG.connectBackend(backend: (LogMessage) -> Unit) = backendWriters().forEach { it.connect(backend) }

fun LOG.disconnectBackend() = backendWriters().forEach { it.disconnect() }

fun LOG.backendWriters() = (ProviderRegistry.getLoggingProvider() as TinylogLoggingProvider).writers.filterIsInstance<BackendWriter>()

fun LOG.printStackTrace(ex: Throwable) = LOG.error(ex)
