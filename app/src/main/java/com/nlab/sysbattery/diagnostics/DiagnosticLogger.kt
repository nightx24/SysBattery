package com.nlab.sysbattery.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, local-only diagnostic log. No data is uploaded automatically.
 * Logs rotate at 512 KiB and exports redact common Android identifiers.
 */
object DiagnosticLogger {
    private const val FILE_NAME = "sysbattery.log"
    private const val MAX_BYTES = 512 * 1024
    private const val ROTATE_NAME = "sysbattery.log.1"

    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (initialized) return
            initialized = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    write("FATAL", "Uncaught exception on thread ${thread.name}", error)
                }
                previous?.uncaughtException(thread, error)
            }
            write("INFO", "Diagnostics initialized; package=${appContext.packageName} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    fun info(message: String) = write("INFO", message)
    fun warn(message: String, error: Throwable? = null) = write("WARN", message, error)
    fun error(message: String, error: Throwable? = null) = write("ERROR", message, error)

    fun readLogs(): String = synchronized(lock) {
        if (!::appContext.isInitialized) return@synchronized "Diagnostics have not been initialized."
        val files = listOf(File(appContext.filesDir, ROTATE_NAME), File(appContext.filesDir, FILE_NAME))
        files.filter { it.exists() }.joinToString("\n") { f ->
            runCatching { f.readText() }.getOrDefault("[Could not read ${f.name}]")
        }.ifBlank { "No logs recorded yet." }
    }

    fun clear() = synchronized(lock) {
        if (::appContext.isInitialized) {
            File(appContext.filesDir, FILE_NAME).delete()
            File(appContext.filesDir, ROTATE_NAME).delete()
        }
        Unit
    }

    private fun write(level: String, message: String, error: Throwable? = null) {
        if (!::appContext.isInitialized) return
        synchronized(lock) {
            runCatching {
                val target = File(appContext.filesDir, FILE_NAME)
                if (target.exists() && target.length() >= MAX_BYTES) {
                    val rotated = File(appContext.filesDir, ROTATE_NAME)
                    rotated.delete()
                    target.renameTo(rotated)
                }
                val throwable = error?.let {
                    StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString()
                }.orEmpty()
                target.appendText("${formatter.format(Date())} [$level] ${message.replace('\n', ' ')}\n${throwable}${if (throwable.isNotEmpty()) "\n" else ""}")
            }
        }
    }
}
