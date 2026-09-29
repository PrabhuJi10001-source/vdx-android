package com.vdx.telemetry

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * UploadWorker — WorkManager job that flushes the local telemetry batch to the
 * telemetry endpoint.
 *
 * - Network-constrained: only runs when there's an unmetered-ish connection
 *   (CONNECTED, so it works over cellular too, but is deferred until the device
 *   is online).
 * - Exponential backoff on failure (WorkManager handles the retry policy).
 * - Never throws — a failed upload just re-queues (the batch file remains).
 *
 * One worker singleton: [enqueue] uses ExistingWorkPolicy.KEEP so concurrent
 * enqueues never cause overlapping upload of the same batch.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "VdxTelemetry-Upload"
        private const val WORK_NAME = "vdx_telemetry_upload"

        /**
         * Schedule a background upload. No-op if telemetry isn't consented —
         * the worker (and thus any network call) simply never runs. KEEP the
         * existing worker if one is already scheduled, so we never pile up.
         */
        fun enqueue(context: Context) {
            try {
                if (Telemetry.isConsented(context)) {
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                    val request = OneTimeWorkRequest.Builder(UploadWorker::class.java)
                        .setConstraints(constraints)
                        // Exponential backoff: 30s → 60s → 120s → ... capped by WM.
                        .setBackoffCriteria(
                            androidx.work.BackoffPolicy.EXPONENTIAL,
                            30,
                            TimeUnit.SECONDS
                        )
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        request
                    )
                }
            } catch (e: Throwable) {
                // WorkManager init can occasionally fail (provider missing) — never crash.
                Log.w(TAG, "enqueue failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun doWork(): Result {
        // Guard: never talk to the network without explicit consent. If consent
        // was revoked while the worker was queued, drop the flush entirely (keep
        // the file local — SessionLogger path).
        if (!Telemetry.isConsented(applicationContext)) {
            Log.d(TAG, "consent not granted — refusing upload")
            return Result.failure() // prevents re-run storm; batch stays local
        }

        val lines = Telemetry.readPendingBatchLines(applicationContext)
        if (lines.isEmpty()) {
            Log.d(TAG, "no telemetry to upload")
            return Result.success()
        }

        val ok = VdxApi.uploadBatch(lines)
        return if (ok) {
            Telemetry.acknowledgeUploaded(applicationContext, lines.size)
            Result.success()
        } else {
            // Network/4xx/5xx — keep the batch file and let WorkManager retry
            // with exponential backoff. Never crash, never drop data.
            Result.retry()
        }
    }
}
