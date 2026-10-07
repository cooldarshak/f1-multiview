package app.f1multiview.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runtime diagnostics owned by the app.
 *
 * A new public log file is created for every process/app launch:
 * Download/F1 MultiView Logs/YYYY-MM-DD/F1MultiView-HH-mm-ss-SSS.log
 *
 * Logging is enabled by default and can be disabled from Settings. The public file is
 * intentionally separate per launch so a crash never overwrites an earlier session.
 *
 * Persistent storage uses MediaStore Downloads on Android 10+ and does not require
 * legacy external-storage permissions.
 */
object AppLogger {
    private const val TAG = "F1MultiView"
    private const val PREFS = "f1_multiview_debug_settings"
    private const val KEY_ENABLED = "app_logging_enabled"
    private const val ROOT_FOLDER = "F1 MultiView Logs"
    private const val MAX_MEMORY_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 4L * 1024L * 1024L

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateFolderFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("HH-mm-ss-SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    @Volatile private var enabled = true
    @Volatile private var initialized = false
    private var appContext: Context? = null
    private var logUri: Uri? = null
    private var logOutput: OutputStream? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        enabled = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

        // Do not reuse an old file: each process gets a distinct launch log.
        if (enabled) {
            ensurePersistentFileLocked("initialize")
        }

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (enabled) {
                e("Crash", "Uncaught exception on ${thread.name}", throwable)
                flush()
                // A crash can happen before normal startup persistence succeeds. Make one
                // final synchronous persistence attempt and flush the complete buffer.
                synchronized(this) {
                    ensurePersistentFileLocked("uncaught-exception")
                    flushBufferedEntriesLocked()
                    flush()
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        initialized = true
        i("AppLogger", "Initialized; enabled=$enabled; logFile=${logUri ?: "unavailable"}")
    }

    fun isEnabled(context: Context): Boolean {
        initialize(context)
        return enabled
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()

        if (value) {
            // Enabling after launch gets its own file as well, so disabled time is never
            // silently merged into an earlier session.
            closeOutput()
            logUri = null
            ensurePersistentFileLocked("logging-enabled")
            i("AppLogger", "Logging enabled; new log file=$logUri")
        } else {
            write("INFO", "AppLogger", "Logging disabled")
            flush()
            closeOutput()
            logUri = null
        }
    }

    fun d(component: String, message: String) = write("DEBUG", component, message)
    fun i(component: String, message: String) = write("INFO", component, message)
    fun w(component: String, message: String) = write("WARN", component, message)
    fun w(component: String, message: String, throwable: Throwable) =
        write("WARN", component, "$message | ${throwable.stackTraceToString()}")
    fun e(component: String, message: String, throwable: Throwable? = null) =
        write("ERROR", component, if (throwable == null) message else "$message | ${throwable.stackTraceToString()}")

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        // Clear means clear the in-app view/current file; it does not delete previous
        // launch logs from Downloads, preserving crash evidence.
        runCatching { logOutput?.flush() }
    }

    fun snapshot(): String = _entries.value.joinToString("\n")

    fun currentLogLocation(): String {
        val uri = logUri ?: return "Download/F1 MultiView Logs/<date>/<launch>.log"
        return uri.toString()
    }

    fun copyToClipboard(context: Context): Boolean {
        val text = snapshot()
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("F1 MultiView logs", text))
        return true
    }

    @Synchronized
    fun close() {
        flush()
        closeOutput()
    }

    private fun write(level: String, component: String, raw: String) {
        if (!enabled) return
        val safe = redact(raw)
        val line = "${formatter.format(Date())} $level [$component] $safe"
        synchronized(this) {
            _entries.value = (_entries.value + line).takeLast(MAX_MEMORY_ENTRIES)
            append(line)
        }
        when (level) {
            "ERROR" -> Log.e(TAG, line)
            "WARN" -> Log.w(TAG, line)
            "INFO" -> Log.i(TAG, line)
            else -> Log.d(TAG, line)
        }
    }

    /**
     * Ensures a persistent Downloads stream exists. If startup creation failed, later log
     * writes retry it instead of permanently leaving the logger memory-only.
     *
     * This must be called while synchronized(this).
     */
    private fun ensurePersistentFileLocked(reason: String): Boolean {
        if (logOutput != null && logUri != null) return true
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Persistent Downloads logging requires Android 10+; API=${Build.VERSION.SDK_INT}; reason=$reason")
            return false
        }

        return runCatching {
            val now = Date()
            val values = ContentValues().apply {
                put(
                    MediaStore.Downloads.DISPLAY_NAME,
                    "F1MultiView-${fileNameFormatter.format(now)}.log"
                )
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_FOLDER + "/" +
                        dateFolderFormatter.format(now)
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")

            try {
                val output = resolver.openOutputStream(uri, "wa")
                    ?: error("MediaStore openOutputStream returned null")

                // Keep the item pending until the stream is proven usable and the startup
                // buffer has been written. This avoids exposing an empty/corrupt file.
                logUri = uri
                logOutput = output
                flushBufferedEntriesLocked()
                output.flush()

                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    },
                    null,
                    null
                )

                Log.i(TAG, "Persistent log created: $uri; reason=$reason")
                true
            } catch (failure: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw failure
            }
        }.onFailure {
            logUri = null
            closeOutput()
            Log.w(TAG, "Unable to create Downloads log file; reason=$reason", it)
        }.getOrDefault(false)
    }

    /**
     * Writes all retained in-memory entries to a newly created stream. This is what makes
     * startup failures recoverable: events generated before MediaStore became available
     * are not lost.
     *
     * This must be called while synchronized(this).
     */
    private fun flushBufferedEntriesLocked() {
        val output = logOutput ?: return
        runCatching {
            _entries.value.forEach { line ->
                output.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
            output.flush()
        }.onFailure {
            Log.w(TAG, "Unable to persist buffered app logs", it)
            closeOutput()
            logUri = null
        }
    }

    private fun append(line: String) {
        if (logOutput == null || logUri == null) {
            ensurePersistentFileLocked("log-write")
        }
        val output = logOutput ?: return
        runCatching {
            if (output is java.io.FileOutputStream && output.channel.size() > MAX_FILE_BYTES) return
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }.onFailure {
            Log.w(TAG, "Unable to write app log", it)
            closeOutput()
            logUri = null
        }
    }

    private fun flush() {
        runCatching { logOutput?.flush() }
    }

    private fun closeOutput() {
        runCatching { logOutput?.close() }
        logOutput = null
    }

    private fun redact(input: String): String {
        return input.replace(
            Regex("(?i)(password|passwd|token|authorization|cookie|secret)[=:]\\s*[^\\s,;]+"),
            "$1=<redacted>"
        )
    }
}
