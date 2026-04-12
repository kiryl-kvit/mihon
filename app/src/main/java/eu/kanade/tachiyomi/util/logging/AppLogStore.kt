package eu.kanade.tachiyomi.util.logging

import android.content.Context
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.toShareIntent
import logcat.LogPriority
import logcat.LogcatLogger
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class AppLogStore(
    private val context: Context,
) {

    private val logsDir = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }
    private val activeLogFile = File(logsDir, ACTIVE_LOG_FILE_NAME)
    private val previousLogFile = File(logsDir, PREVIOUS_LOG_FILE_NAME)
    private val lock = Any()

    fun logger(minPriority: LogPriority): LogcatLogger {
        return AppLogLogger(this, minPriority)
    }

    fun append(priority: LogPriority, tag: String, message: String) {
        val timestamp = OffsetDateTime.now(ZoneId.systemDefault()).format(TIMESTAMP_FORMATTER)
        val sanitizedMessage = message.replace("\r\n", "\n")
        val entry = buildString {
            append(timestamp)
            append(' ')
            append(priority.name.first())
            append('/')
            append(tag)
            append(": ")
            append(sanitizedMessage)
            appendLine()
        }

        synchronized(lock) {
            logsDir.mkdirs()
            rotateIfNeeded(entry.toByteArray(Charsets.UTF_8).size)
            activeLogFile.appendText(entry)
        }
    }

    fun readLogs(): String {
        synchronized(lock) {
            return readLogsLocked().trim()
        }
    }

    fun clear() {
        synchronized(lock) {
            activeLogFile.delete()
            previousLogFile.delete()
        }
    }

    suspend fun shareLogs(
        header: String? = null,
    ) = withIOContext {
        val file = createExportFile(header)
        val uri = file.getUriCompat(context)
        withUIContext {
            context.startActivity(uri.toShareIntent(context, "text/plain"))
        }
    }

    fun getLogText(): String {
        return readLogs().ifBlank { NO_LOGS_MESSAGE }
    }

    private fun rotateIfNeeded(incomingSize: Int) {
        if (activeLogFile.exists() && activeLogFile.length() + incomingSize <= MAX_LOG_FILE_BYTES) {
            return
        }

        if (previousLogFile.exists()) {
            previousLogFile.delete()
        }
        if (activeLogFile.exists()) {
            activeLogFile.renameTo(previousLogFile)
        }
        activeLogFile.delete()
    }

    private fun createExportFile(header: String?): File {
        return synchronized(lock) {
            val exportFile = context.createFileInCacheDir(EXPORT_LOG_FILE_NAME)
            exportFile.bufferedWriter().use { writer ->
                header?.takeIf { it.isNotBlank() }?.let {
                    writer.appendLine(it)
                    writer.appendLine()
                }

                val logs = readLogsLocked().trim()
                writer.append(if (logs.isBlank()) NO_LOGS_MESSAGE else logs)
                if (!logs.endsWith('\n')) {
                    writer.appendLine()
                }
            }
            exportFile
        }
    }

    private fun readLogsLocked(): String {
        val previous = previousLogFile.takeIf(File::exists)?.readText().orEmpty()
        val current = activeLogFile.takeIf(File::exists)?.readText().orEmpty()
        return buildString {
            if (previous.isNotBlank()) {
                append(previous)
                if (!previous.endsWith('\n')) appendLine()
            }
            append(current)
        }
    }

    private class AppLogLogger(
        private val store: AppLogStore,
        private val minPriority: LogPriority,
    ) : LogcatLogger {
        override fun isLoggable(priority: LogPriority, tag: String): Boolean {
            return priority.priorityInt >= minPriority.priorityInt
        }

        override fun log(priority: LogPriority, tag: String, message: String) {
            store.append(priority, tag, message)
        }
    }

    companion object {
        private const val LOG_DIR_NAME = "logs"
        private const val ACTIVE_LOG_FILE_NAME = "mihon-app.log"
        private const val PREVIOUS_LOG_FILE_NAME = "mihon-app.log.1"
        private const val EXPORT_LOG_FILE_NAME = "mihon_logs.txt"
        private const val MAX_LOG_FILE_BYTES = 512 * 1024L
        const val NO_LOGS_MESSAGE = "No logs captured yet."

        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd HH:mm:ss.SSS xxx",
            Locale.US,
        )
    }
}
