package com.ettlinger.wearrecorder

import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/** Keeps recording headroom, reclaiming only cache through the Android allocator. */
internal class RecordingStorage(private val manager: StorageManager) {
    private var lastAllocationAttempt = -60_000L

    fun freeBytes(directory: File): Long = StatFs(directory.absolutePath).availableBytes

    fun hasHeadroom(directory: File, minimumBytes: Long): Boolean {
        if (freeBytes(directory) >= minimumBytes) return true
        val now = SystemClock.elapsedRealtime()
        // Android limits allocation attempts for unbounded recordings to once a minute.
        if (now - lastAllocationAttempt < 60_000L) return false
        lastAllocationAttempt = now
        return try {
            val volume = manager.getUuidForPath(directory)
            if (manager.getAllocatableBytes(volume) < minimumBytes) return false
            manager.allocateBytes(volume, minimumBytes)
            true
        } catch (error: IOException) {
            AppLog.w(RecordingService.TAG, "Unable to allocate recording headroom; pausing capture")
            false
        }
    }
}
