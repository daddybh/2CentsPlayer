package com.twocents.player.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun interface RadioDiagnosticLogger {
    fun debug(message: String)
}

object NoOpRadioDiagnosticLogger : RadioDiagnosticLogger {
    override fun debug(message: String) = Unit
}

data class RadioDiagnosticEntry(
    val timestampMs: Long,
    val message: String,
)

internal class RadioDiagnosticHistory(
    private val loadRaw: () -> String?,
    private val saveRaw: (String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maximumEntries: Int = MAX_ENTRIES,
) {
    constructor(
        preferences: SharedPreferences,
        clock: () -> Long = { System.currentTimeMillis() },
        maximumEntries: Int = MAX_ENTRIES,
    ) : this(
        loadRaw = { preferences.getString(KEY_ENTRIES, null) },
        saveRaw = { raw -> preferences.edit().putString(KEY_ENTRIES, raw).apply() },
        clock = clock,
        maximumEntries = maximumEntries,
    )

    @Synchronized
    fun append(message: String) {
        val entries = (readRecentInternal() + RadioDiagnosticEntry(clock(), message))
            .takeLast(maximumEntries.coerceAtLeast(1))
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put(KEY_TIMESTAMP_MS, entry.timestampMs)
                    .put(KEY_MESSAGE, entry.message),
            )
        }
        saveRaw(array.toString())
    }

    @Synchronized
    fun readRecent(): List<RadioDiagnosticEntry> = readRecentInternal()

    private fun readRecentInternal(): List<RadioDiagnosticEntry> {
        val raw = loadRaw() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val message = item.optString(KEY_MESSAGE).trim()
                if (message.isBlank()) continue
                add(
                    RadioDiagnosticEntry(
                        timestampMs = item.optLong(KEY_TIMESTAMP_MS),
                        message = message,
                    ),
                )
            }
        }.takeLast(maximumEntries.coerceAtLeast(1))
    }

    private companion object {
        const val KEY_ENTRIES = "radio_diagnostic_entries_v1"
        const val KEY_TIMESTAMP_MS = "timestampMs"
        const val KEY_MESSAGE = "message"
        const val MAX_ENTRIES = 100
    }
}

class AndroidRadioDiagnosticLogger(context: Context) : RadioDiagnosticLogger {
    private val history = RadioDiagnosticHistory(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    private val exportedLogFile = runCatching { diagnosticsFile(context) }.getOrNull()

    override fun debug(message: String) {
        runCatching { Log.d(TAG, message) }
        runCatching {
            history.append(message)
            exportRecentEntries()
        }
    }

    fun readRecent(): List<RadioDiagnosticEntry> = history.readRecent()

    private fun exportRecentEntries() {
        val targetFile = exportedLogFile ?: return
        targetFile.parentFile?.mkdirs()
        val content = history.readRecent().joinToString(separator = "\n") { entry ->
            JSONObject()
                .put("timestampMs", entry.timestampMs)
                .put("message", entry.message)
                .toString()
        }
        targetFile.writeText(content)
    }

    companion object {
        fun diagnosticsFile(context: Context): File? {
            return context.getExternalFilesDir(null)?.resolve(EXPORTED_FILE_NAME)
        }

        private const val TAG = "RadioEngine"
        private const val PREFERENCES_NAME = "two_cents_player_diagnostics"
        private const val EXPORTED_FILE_NAME = "radio_diagnostics.jsonl"
    }
}
