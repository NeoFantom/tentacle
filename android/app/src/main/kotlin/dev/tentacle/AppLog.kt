package dev.tentacle

import android.content.Context
import android.os.Build
import dev.tentacle.core.Redactor
import dev.tentacle.core.RotatingLog
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.TimeZone

/** Process-wide private application log. Writes are synchronous so crash entries reach disk. */
object AppLog {
    @Volatile private var log: RotatingLog? = null
    @Volatile private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (log != null) return
        val app = context.applicationContext
        val settings = Settings(app)
        appContext = app
        log = RotatingLog(
            directory = java.io.File(app.filesDir, "logs"),
            redactor = Redactor { listOf(settings.token) },
        )
    }

    fun append(message: String) {
        runCatching { log?.append(message) }
    }

    fun snapshot(): String {
        if (appContext == null) return ""
        val header = buildString {
            appendLine("Tentacle app log")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time zone: ${TimeZone.getDefault().id}")
            appendLine("Current time: ${Instant.now()}")
            appendLine()
        }
        return header + (log?.snapshot() ?: "")
    }

    fun clear() {
        log?.clear()
    }
}

internal class CrashLoggingHandler(
    private val append: (String) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            append("CRASH thread=${thread.name}\n$trace")
        } finally {
            if (delegate !== this) delegate?.uncaughtException(thread, error)
        }
    }
}
