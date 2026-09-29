package com.vdx.telemetry

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * VdxApi — the single telemetry upload endpoint client.
 *
 * One endpoint: POST <base>/events. The base is a placeholder
 * (https://telemetry.vdx.dev) that IDA replaces with the real production domain
 * before launch; there is intentionally no real endpoint wired here.
 *
 * Hard rules:
 *  - NEVER throws to its caller — any network/IO failure is swallowed and
 *    reported as false (caller keeps the batch queued).
 *  - Never blocks app startup (this is only ever called from the UploadWorker).
 *  - Does NOT retry or back off itself — the WorkManager worker owns the
 *    retry/backoff policy.
 */
object VdxApi {
    private const val TAG = "VdxApi"
    private const val ENDPOINT_PATH = "/events"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Placeholder domain — flag for IDA to replace before launch. */
    var baseUrl: String = "https://telemetry.vdx.dev"

    /**
     * Upload a JSONL batch (each event already JSON-serialized, one per line).
     * Returns true on any HTTP 2xx response. On 4xx/5xx/network failure returns
     * false so the caller retains the batch for a later attempt.
     */
    fun uploadBatch(jsonLines: List<String>): Boolean {
        if (jsonLines.isEmpty()) return true
        return try {
            val urlString = baseUrl.trimEnd('/') + ENDPOINT_PATH
            val conn = URL(urlString).openConnection() as HttpURLConnection
            val body = jsonLines.joinToString("\n")
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty(
                    "User-Agent",
                    "VDX-Android/${telemetryAppVersion ?: "unknown"}"
                )
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                DataOutputStream(conn.outputStream).use { it.writeBytes(body) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Log.d(TAG, "upload ok (${jsonLines.size} events, http $code)")
                    true
                } else {
                    // 4xx/5xx — keep queueing per spec. Log status only, no body
                    // (a body could echo a payload). Never crash.
                    Log.w(TAG, "upload rejected, http $code — keeping batch queued")
                    false
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "upload failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Version tag for the User-Agent; the caller sets this at init. */
    @Volatile
    var telemetryAppVersion: String? = null
}
