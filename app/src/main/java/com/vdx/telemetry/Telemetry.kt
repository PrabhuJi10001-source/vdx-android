package com.vdx.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Telemetry — VDX essential-events metrics pipe (DPDP-clean).
 *
 * The ONLY data that ever leaves the device is the strict telemetry contract:
 * coarse event types + a short allowlist of enum/boolean/number fields. Every
 * event passes through [TelemetrySanitizer] before it is queued, so transcripts,
 * audio, message bodies, contact names/numbers, search queries, screen contents,
 * typed text, and API keys can never reach the endpoint.
 *
 * Consent model (opt-in on second app open, default OFF):
 *  - Until consent is granted, only `install` and `consent_granted` /
 *    `consent_denied` events are queued for upload. All other events written by
 *    callers are discarded (they're still captured locally by SessionLogger —
 *    the spec's "events stay local" path).
 *  - The UploadWorker refuses to make any network call unless [isConsented].
 *  - consent_granted / consent_denied are logged by [setConsent] regardless of
 *    the previous state, so the funnel is never lost.
 *
 * No third-party SDK, no DI, no analytics library. One object, one sanitizer,
 * one worker.
 */
object Telemetry {
    private const val TAG = "VdxTelemetry"
    private const val PREFS = "vdx_telemetry_prefs"
    private const val KEY_CONSENTED = "consented"
    private const val KEY_DECIDED = "consent_decided"
    private const val KEY_INSTALL_UUID = "install_uuid"
    private const val KEY_INSTALL_ENQUEUED = "install_enqueued"

    /** Pending batch file (JSONL, one event per line), before upload. */
    private const val BATCH_FILE_NAME = "telemetry_pending.jsonl"
    private const val MAX_BATCH_EVENTS = 200
    private const val FLUSH_AT = 20

    private val UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var prefs: SharedPreferences? = null
    @Volatile
    private var appVersion: String = "unknown"
    @Volatile
    private var installUuid: String = ""
    private val writeLock = Any()
    private val flushLock = Any()

    /** Call once from Application.onCreate. Safe to call multiple times. */
    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    installUuid = ensureInstallUuid(context)
                    appVersion = readVersion(context)
                    VdxApi.telemetryAppVersion = appVersion
                    ensureInstallEvent()
                    installCrashHook()
                }
            }
        }
        // Every app start nudges the upload worker (flushes any crash/consent
        // events from a prior run). No-op when consent isn't granted.
        UploadWorker.enqueue(context)
    }

    // ──────────────────────────────────────────────────────────────
    // Consent
    // ──────────────────────────────────────────────────────────────

    fun isConsented(context: Context): Boolean =
        prefsOf(context).getBoolean(KEY_CONSENTED, false)

    /** True once the user has answered the one-time opt-in consent prompt. */
    fun hasConsentDecision(context: Context): Boolean =
        prefsOf(context).getBoolean(KEY_DECIDED, false)

    /**
     * Record a consent decision. [granted]=true → telemetry is enabled and the
     * batch may upload. Always logs the consent event itself (it is one of the
     * only events allowed before consent) then flushes. Marks the decision as
     * made so the prompt is not re-shown.
     */
    fun setConsent(context: Context, granted: Boolean) {
        val p = prefsOf(context)
        if (p.getBoolean(KEY_CONSENTED, false) == granted &&
            p.getBoolean(KEY_DECIDED, false)) return
        p.edit()
            .putBoolean(KEY_CONSENTED, granted)
            .putBoolean(KEY_DECIDED, true)
            .apply()
        val type = if (granted) TelemetryEventTypes.CONSENT_GRANTED
                    else TelemetryEventTypes.CONSENT_DENIED
        logLocal(type, emptyMap())
        enqueue(context)
    }

    // ──────────────────────────────────────────────────────────────
    // Event submission (caller-facing)
    // ──────────────────────────────────────────────────────────────

    /**
     * Submit an event. [type] must be a known telemetry event type; [fields]
     * must use allowlist keys. Everything is sanitized; unknown fields are
     * dropped. If consent isn't granted, only `install` / consent events are
     * kept for upload — everything else is logged locally (SessionLogger) and
     * not queued. Never throws.
     */
    fun log(type: String, fields: Map<String, Any?>) {
        val ctx = appContext ?: return
        try {
            val sanitized = TelemetrySanitizer.sanitize(type, fields)
            val canUploadBeforeConsent = type == TelemetryEventTypes.INSTALL ||
                type == TelemetryEventTypes.CONSENT_GRANTED ||
                type == TelemetryEventTypes.CONSENT_DENIED
            // Pre-consent, only install/consent events are allowed to reach the
            // queue; anything else is captured locally by SessionLogger only.
            if (!isConsented(ctx) && !canUploadBeforeConsent) return
            appendPending(type, sanitized.fields)
            maybeFlush(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "log failed", e)
        }
    }

    /**
     * Log a consent decision unconditionally into the queue (used by
     * [setConsent]); bypasses the pre-consent gating.
     */
    private fun logLocal(type: String, fields: Map<String, Any?>) {
        val ctx = appContext ?: return
        try {
            val sanitized = TelemetrySanitizer.sanitize(type, fields)
            appendPending(type, sanitized.fields)
            maybeFlush(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "logLocal failed", e)
        }
    }

    @Volatile
    private var engineSelectionLogged = false

    /**
     * Log the engine-selection snapshot once per process (the active ASR / LLM
     * providers and whether a BYOK key is configured). Engine names only —
     * coarse enums, never transcript content.
     */
    fun logEngineSelectionOnce(
        asr: String?,
        llm: String?,
        key: String?
    ) {
        if (engineSelectionLogged) return
        synchronized(this) {
            if (engineSelectionLogged) return
            engineSelectionLogged = true
            log(
                TelemetryEventTypes.ENGINE_SELECTED,
                mapOf(
                    "asr" to asr,
                    "llm" to llm,
                    "key" to key
                )
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Batch file management
    // ──────────────────────────────────────────────────────────────

    private fun batchFile(context: Context): File =
        File(context.filesDir, BATCH_FILE_NAME)

    private fun appendPending(type: String, fields: Map<String, Any?>) {
        val ctx = appContext ?: return
        synchronized(writeLock) {
            try {
                val file = batchFile(ctx)
                if (pendingCount(ctx) >= MAX_BATCH_EVENTS) {
                    Log.w(TAG, "batch full ($MAX_BATCH_EVENTS) — dropping event $type")
                    return
                }
                if (!file.exists()) file.createNewFile()
                val entry = JSONObject()
                    .put("v", appVersion)
                    .put("did", installUuid)
                    .put("ts", UTC.format(Date()))
                    .put("e", type)
                    .put("fields", JSONObject(fields))
                FileOutputStream(file, true).use { os ->
                    os.write(entry.toString().toByteArray(Charsets.UTF_8))
                    os.write('\n'.code)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "appendPending failed", e)
            }
        }
    }

    private fun pendingCount(ctx: Context): Int {
        val f = batchFile(ctx)
        if (!f.exists()) return 0
        return try {
            f.useLines { lines -> lines.count { it.isNotBlank() } }
        } catch (_: Throwable) { 0 }
    }

    /** Read all pending batch lines (without removing), for the worker. */
    fun readPendingBatchLines(context: Context): List<String> {
        val f = batchFile(context)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Called by the worker after a successful upload. Removes the uploaded
     * batch lines atomically: reads the file, drops the first [count] lines that
     * were uploaded, and rewrites the remainder (so concurrent appends during
     * the upload are preserved).
     */
    fun acknowledgeUploaded(context: Context, count: Int) {
        synchronized(writeLock) {
            try {
                val f = batchFile(context)
                if (!f.exists()) return
                val lines = f.readLines().filter { it.isNotBlank() }
                val keep = if (count >= lines.size) emptyList()
                           else lines.drop(count)
                if (keep.isEmpty()) {
                    f.delete()
                } else {
                    FileOutputStream(f, false).use { os ->
                        keep.forEach { os.write((it + "\n").toByteArray(Charsets.UTF_8)) }
                    }
                }
            } catch (e: Throwable) {
                // Never drop data on a failed rewrite — leave the file intact.
                Log.w(TAG, "acknowledgeUploaded failed (retaining batch)", e)
            }
        }
    }

    private fun maybeFlush(ctx: Context) {
        synchronized(flushLock) {
            if (pendingCount(ctx) >= FLUSH_AT) {
                enqueue(ctx)
            }
        }
    }

    /** Schedule the upload worker. Public so lifecycle hooks can flush on app background. */
    fun flush(context: Context) = enqueue(context)

    private fun enqueue(context: Context) = UploadWorker.enqueue(context)

    // ──────────────────────────────────────────────────────────────
    // First launch / install event
    // ──────────────────────────────────────────────────────────────

    private fun ensureInstallEvent() {
        val ctx = appContext ?: return
        val p = prefsOf(ctx)
        if (p.getBoolean(KEY_INSTALL_ENQUEUED, false)) return
        p.edit().putBoolean(KEY_INSTALL_ENQUEUED, true).apply()
        installUuid = p.getString(KEY_INSTALL_UUID, installUuid) ?: installUuid
        // install carries only identity metadata (v/did/ts) — no fields.
        appendPending(TelemetryEventTypes.INSTALL, emptyMap())
        enqueue(ctx)
    }

    private fun ensureInstallUuid(context: Context): String {
        val p = prefsOf(context)
        return p.getString(KEY_INSTALL_UUID, null) ?: UUID.randomUUID().toString()
            .also { p.edit().putString(KEY_INSTALL_UUID, it).apply() }
    }

    private fun readVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    // ──────────────────────────────────────────────────────────────
    // Crash hook
    // ──────────────────────────────────────────────────────────────

    @Volatile
    private var crashHookInstalled = false

    /**
     * Wrap the default uncaught-exception handler to send a crash event
     * (exception class + top 3 stack frames ONLY) then delegate to the original,
     * so normal crash flow (logcat, ANR dialog) is preserved. No third-party SDK.
     */
    private fun installCrashHook() {
        if (crashHookInstalled) return
        synchronized(this) {
            if (crashHookInstalled) return
            val original = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val frames = throwable.stackTrace?.take(3)
                        ?.map { frameToString(it) }
                        ?.joinToString(" | ")
                        .orEmpty()
                    log(
                        TelemetryEventTypes.CRASH,
                        mapOf(
                            "exception_class" to (throwable.javaClass.name ?: "Unknown"),
                            "stack_top3_frames" to frames.take(400)
                        )
                    )
                } catch (_: Throwable) { /* never mask the crash */ }
                // Delegate to the original handler (may be null → default).
                original?.uncaughtException(thread, throwable)
            }
            crashHookInstalled = true
        }
    }

    private fun frameToString(f: StackTraceElement): String =
        "${f.className}.${f.methodName}:${f.lineNumber}"

    private fun prefsOf(context: Context): SharedPreferences {
        prefs?.let { return it }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
