package dev.ahmedmohamed.hayaitts.tts

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small on-device ring buffer. No spoken text, credentials, or model paths are recorded. */
object AtlasTtsDiagnostics {
    private const val PREFS = "atlas_tts_diagnostics"
    private const val KEY = "recent_events"
    private const val MAX_LINES = 90

    @Synchronized
    fun record(context: Context, event: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp | $event"
        val lines = prefs.getString(KEY, "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.toList().takeLast(MAX_LINES - 1)
        prefs.edit().putString(KEY, (lines + line).joinToString("\n")).apply()
    }

    fun read(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().ifBlank { "No speech requests recorded yet." }
}
