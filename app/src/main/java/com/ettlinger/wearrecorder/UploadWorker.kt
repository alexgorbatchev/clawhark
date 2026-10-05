package com.ettlinger.wearrecorder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "Upload"
        const val WORK_NAME = "upload_recordings"
        const val WORK_TAG = "clawhark_upload"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppLog.init(applicationContext)
        AuthManager.init(applicationContext)
        val dir = File(applicationContext.filesDir, "recordings")
        if (!dir.exists()) return@withContext Result.success()
        try {
            RandomAccessFile(File(dir, ".upload.lock"), "rw").use { handle ->
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return@withContext Result.retry()
                lock.use { uploadQueue(dir) }
            }
        } catch (error: IOException) {
            AppLog.e(TAG, "Cannot lock upload queue; recordings preserved", error)
            Result.retry()
        }
    }

    private suspend fun uploadQueue(dir: File): Result {
        // The OS lock proves there is no active uploader, regardless of the audio file's age.
        for (claimed in dir.listFiles().orEmpty().filter { it.name.endsWith(".m4a.uploading") }) {
            val original = File(dir, claimed.name.removeSuffix(".uploading"))
            if (original.exists() || !claimed.renameTo(original)) {
                AppLog.e(TAG, "Cannot restore upload claim ${claimed.name}; preserving both files")
                return Result.retry()
            }
        }
        if (!AuthManager.isAuthenticated()) return Result.success()
        val files = dir.listFiles().orEmpty().filter { it.extension == "m4a" }.sortedBy { it.name }
        val uploader = DriveUploader()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (!AuthManager.isAuthenticated()) return Result.success()
            val claimed = File(dir, file.name + ".uploading")
            if (!file.renameTo(claimed)) return Result.retry()
            try {
                if (!uploader.uploadFile(claimed)) return Result.retry()
                if (!claimed.delete()) return Result.retry()
                DriveUploader.receipt(claimed).delete()
                AppLog.i(TAG, "Uploaded and verified ${file.name}")
            } finally {
                // Cancellation, HTTP failures, and local cleanup failures all preserve the queue.
                if (claimed.exists() && (file.exists() || !claimed.renameTo(file))) {
                    AppLog.e(TAG, "Could not restore ${claimed.name}; it remains queued for recovery")
                }
            }
        }
        return Result.success()
    }
}
