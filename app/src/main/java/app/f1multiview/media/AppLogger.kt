package app.f1multiview.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.BaseColumns
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
 * Logs are kept in Download/F1 MultiView Logs. A log is reused across app launches and
 * rotated only when it exceeds 5 MiB. This deliberately avoids tiny per-session files.
 * Persistent storage uses MediaStore Downloads on Android 10+.
 */
object AppLogger {
    private const val TAG = "F1MultiView"
    private const val PREFS = "f1_multiview_debug_settings"
    private const val KEY_ENABLED = "app_logging_enabled"
    private const val ROOT_FOLDER = "F1 MultiView Logs"
    private const val MAX_MEMORY_ENTRIES = 2000
    private const val MAX_FILE_BYTES = 5L * 1024L * 1024L
    private const val BASE_FILE_NAME = "F1MultiView.log"

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dateFolderFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fileNameFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    @Volatile private var enabled = true
    @Volatile private var initialized = false
    private var appContext: Context? = null
    private var logUri: Uri? = null
    private var logOutput: OutputStream? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var logBytes: Long = 0L

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        enabled = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

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
        i("AppLogger", "===== SESSION START ${formatter.format(Date())} =====")
    }

    fun isEnabled(context: Context): Boolean {
        initialize(context)
        return enabled
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()

        if (value) {
            enabled = true
            ensurePersistentFileLocked("logging-enabled")
            i("AppLogger", "Logging enabled; log file=$logUri")
        } else {
            if (enabled) write("INFO", "AppLogger", "Logging disabled")
            flush()
            enabled = false
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

    @Synchronized
    fun hasPersistentStorage(): Boolean = logUri != null && logOutput != null

    @Synchronized
    fun retryPersistentStorage(context: Context): Boolean {
        if (!enabled) return false
        appContext = context.applicationContext
        return ensurePersistentFileLocked("permission-retry")
    }

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        runCatching {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(BaseColumns._ID, MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_FOLDER + "/"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)).coerceAtLeast(0L)
                        if (size < MAX_FILE_BYTES) {
                            val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                            val output = resolver.openOutputStream(uri, "wa")
                            if (output != null) { logUri = uri; logOutput = output; logBytes = size; return true }
                        }
                    }
                }
        }.onFailure { Log.w(TAG, "Unable to find existing Downloads log; reason=$reason", it) }
        return runCatching {
            val now = Date()
            val displayName = if (reason == "initialize" && !hasAnyLogLocked(resolver)) BASE_FILE_NAME
                else "F1MultiView-${fileNameFormatter.format(now)}.log"
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_FOLDER + "/"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName); put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert returned null")
            try {
                val output = resolver.openOutputStream(uri, "wa") ?: error("MediaStore openOutputStream returned null")
                logUri = uri; logOutput = output; logBytes = 0L; output.flush()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                true
            } catch (failure: Throwable) { runCatching { resolver.delete(uri, null, null) }; throw failure }
        }.onFailure { logUri = null; logBytes = 0L; closeOutput(); Log.w(TAG, "Unable to create Downloads log file; reason=$reason", it) }.getOrDefault(false)
    }

    private fun hasAnyLogLocked(resolver: android.content.ContentResolver): Boolean = runCatching {
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(BaseColumns._ID),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
            arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + ROOT_FOLDER + "/"), null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun append(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(this) {
            if (logOutput == null || logUri == null) ensurePersistentFileLocked("log-write")
            if (logOutput == null || logUri == null) return
            if (logBytes + bytes.size > MAX_FILE_BYTES) { closeOutput(); logUri = null; logBytes = 0L; ensurePersistentFileLocked("size-rotation") }
            val output = logOutput ?: return
            runCatching { output.write(bytes); output.flush(); logBytes += bytes.size }
                .onFailure { Log.w(TAG, "Unable to write app log", it); closeOutput() }
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
