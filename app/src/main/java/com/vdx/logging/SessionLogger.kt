package com.vdx.logging

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SessionLogger — structured JSON logging for VDX.
 *
 * Writes every agent event to a rotating log file under
 * context.filesDir/vdx-logs/. Each session gets its own file.
 *
 * Design:
 * - JSON Lines format (one JSON object per line) — easy to parse, grep, stream.
 * - Rotating: max 5 files, 500KB each. Oldest deleted on rotation.
 * - Thread-safe via synchronized writes.
 * - No external dependencies — pure Android SDK.
 *
 * Usage:
 *   val logger = SessionLogger(context, "session-20260723-143022")
 *   logger.log("INTENT", mapOf("action" to "call", "contact" to "Mom"))
 *   logger.log("EXECUTE", mapOf("action" to "click", "target" to "dial button"))
 *   logger.log("RESULT", mapOf("status" to "success", "duration_ms" to 1200))
 *   logger.close()
 */
class SessionLogger private constructor(
    private val logFile: File
) {
    companion object {
        private const val TAG = "VDX-Logger"
        private const val MAX_FILE_SIZE = 500 * 1024 // 500 KB
        private const val MAX_FILES = 5
        private const val LOG_DIR = "vdx-logs"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Create a new session logger.
         * @param context Android context (for filesDir)
         * @param sessionId Unique session identifier (e.g. "session-20260723-143022")
         */
        fun create(context: Context, sessionId: String): SessionLogger {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            rotateIfNeeded(dir)
            val file = File(dir, "$sessionId.jsonl")
            return SessionLogger(file)
        }

        /**
         * List all session log files, newest first.
         */
        fun listSessions(context: Context): List<File> {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.extension == "jsonl" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        /**
         * Read a session log as a list of JSON objects.
         */
        fun readSession(file: File): List<JSONObject> {
            if (!file.exists()) return emptyList()
            val lines = file.readLines()
            return lines.mapNotNull { line ->
                try {
                    JSONObject(line)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse log line: ${line.take(100)}")
                    null
                }
            }
        }

        /**
         * Delete old log files when we exceed MAX_FILES.
         */
        private fun rotateIfNeeded(dir: File) {
            val files = dir.listFiles()
                ?.filter { it.extension == "jsonl" }
                ?.sortedByDescending { it.lastModified() }
                ?.toMutableList()
                ?: return
            while (files.size >= MAX_FILES) {
                val oldest = files.removeLastOrNull() ?: break
                oldest.delete()
                Log.d(TAG, "Rotated out: ${oldest.name}")
            }
        }
    }

    private val lock = Any()

    /** Session identifier derived from the filename. */
    val sessionId: String get() = logFile.nameWithoutExtension

    /**
     * Log a structured event.
     * @param eventType Short event type (e.g. "INTENT", "EXECUTE", "VERIFY", "RESULT", "ERROR")
     * @param data Key-value pairs describing the event
     */
    fun log(eventType: String, data: Map<String, Any?>) {
        synchronized(lock) {
            try {
                val entry = JSONObject().apply {
                    put("ts", DATE_FORMAT.format(Date()))
                    put("type", eventType)
                    val payload = JSONObject()
                    data.forEach { (k, v) ->
                        when (v) {
                            is String -> payload.put(k, v)
                            is Number -> payload.put(k, v)
                            is Boolean -> payload.put(k, v)
                            is JSONObject -> payload.put(k, v)
                            is JSONArray -> payload.put(k, v)
                            null -> payload.put(k, JSONObject.NULL)
                            else -> payload.put(k, v.toString())
                        }
                    }
                    put("data", payload)
                }
                logFile.appendText(entry.toString() + "\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log entry", e)
            }
        }
    }

    /**
     * Log an error event with stack trace.
     */
    fun logError(eventType: String, message: String, error: Throwable? = null) {
        val data = mutableMapOf<String, Any?>("message" to message)
        if (error != null) {
            data["error"] = error::class.simpleName ?: "Unknown"
            data["stack"] = Log.getStackTraceString(error).take(500)
        }
        log(eventType, data)
    }

    /**
     * Close the logger. Currently a no-op (file is flushed per write),
     * but exists for future buffered-write implementations.
     */
    fun close() {
        // No-op for now — each write is flushed.
        Log.d(TAG, "Session $sessionId closed")
    }
}
