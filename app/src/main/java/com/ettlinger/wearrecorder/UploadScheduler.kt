package com.ettlinger.wearrecorder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal object UploadScheduler {
    const val UPLOAD_INTERVAL_MINUTES = 60L
    const val UPLOAD_FALLBACK_INTERVAL_HOURS = 4L
    const val UPLOAD_FALLBACK_WORK_NAME = "upload_fallback"

    // ─── Upload Scheduling ───────────────────────────────────────────────

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        // Primary: upload on WiFi (every 60min)
        val wifiConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val wifiWork = PeriodicWorkRequestBuilder<UploadWorker>(
            UPLOAD_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).setConstraints(wifiConstraints).addTag(UploadWorker.WORK_TAG).build()
        wm.enqueueUniquePeriodicWork(
            UploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            wifiWork
        )

        // Fallback: upload on any network (every 4h) — handles Bluetooth proxy when WiFi is off
        val anyNetConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val fallbackWork = PeriodicWorkRequestBuilder<UploadWorker>(
            UPLOAD_FALLBACK_INTERVAL_HOURS, TimeUnit.HOURS
        ).setConstraints(anyNetConstraints).addTag(UploadWorker.WORK_TAG).build()
        wm.enqueueUniquePeriodicWork(
            UPLOAD_FALLBACK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            fallbackWork
        )

        AppLog.i("Upload", "Upload scheduled: every ${UPLOAD_INTERVAL_MINUTES}min (WiFi) + every ${UPLOAD_FALLBACK_INTERVAL_HOURS}h (any network)")
    }

    fun uploadPending(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .addTag(UploadWorker.WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload_immediate",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeWork
        )
        AppLog.i("Upload", "One-time upload enqueued for remaining files")
    }

    fun stopPeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UploadWorker.WORK_NAME)
        wm.cancelUniqueWork(UPLOAD_FALLBACK_WORK_NAME)
    }
}
