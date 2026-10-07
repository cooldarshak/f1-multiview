package app.f1multiview.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic logger.
 *
 * Enabled by default so runtime failures can be diagnosed without requiring logcat.
 * The same entries are also sent to Logcat while enabled. Sensitive fields are redacted
 * before storage/display. A bounded on-disk file survives process crashes/restarts.
 */
object AppLogger {
    private const val TAG = "F1MultiView"
    private const val PREFS = "f1_multiview_debug_settings"
    private const val KEY_ENABLED = "app_logging_enabled"
    private const val FILE_NAME = "f1-multiview.log"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_MEMORY_ENTRIES = 2000

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    @Volatile
    private var enabled = true
    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        enabled = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        loadPersisted()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (enabled) {
                e("Crash", "Uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}")
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        initialized = true
        i("AppLogger", "Initialized; enabled=$enabled")
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
            .edit()
            .putBoolean(KEY_ENABLED, value)
            .apply()
        if (value) i("AppLogger", "Logging enabled")
    }

    fun d(component: String, message: String) = write("DEBUG", component, message)
    fun i(component: String, message: String) = write("INFO", component, message)
    fun w(component: String, message: String) = write("WARN", component, message)
    fun e(component: String, message: String, throwable: Throwable? = null) =
        write("ERROR", component, if (throwable == null) message else "$message | ${throwable.stackTraceToString()}")

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        appContext?.let { File(it.filesDir, FILE_NAME).delete() }
    }

    fun snapshot(): String = _entries.value.joinToString("\n")

    fun copyToClipboard(context: Context): Boolean {
        val text = snapshot()
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("F1 MultiView logs", text))
        return true
    }

    private fun write(level: String, component: String, raw: String) {
        if (!enabled) return
        val safe = redact(raw)
        val line = "${formatter.format(Date())} $level [$component] $safe"
        synchronized(this) {
            val next = (_entries.value + line).takeLast(MAX_MEMORY_ENTRIES)
            _entries.value = next
            appContext?.let { persist(it, line) }
        }
        when (level) {
            "ERROR" -> Log.e(TAG, line)
            "WARN" -> Log.w(TAG, line)
            "INFO" -> Log.i(TAG, line)
            else -> Log.d(TAG, line)
        }
    }

    private fun loadPersisted() {
        val context = appContext ?: return
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        _entries.value = lines.takeLast(MAX_MEMORY_ENTRIES)
    }

    private fun persist(context: Context, line: String) {
        val file = File(context.filesDir, FILE_NAME)
        runCatching {
            file.appendText(line + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                val retained = file.readLines().takeLast(MAX_MEMORY_ENTRIES).joinToString("\n", postfix = "\n")
                file.writeText(retained)
            }
        }
    }

    private fun redact(input: String): String {
        return input
            .replace(Regex("(?i)(password|passwd|token|authorization|cookie|secret)[=:]\\s*[^\\s,;]+"), "$1=<redacted>")
    }
}
