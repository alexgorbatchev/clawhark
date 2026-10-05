package com.ettlinger.wearrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaFormat
import android.media.MediaExtractor
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {

    companion object {
        const val TAG = "Service"
        const val CHANNEL_ID = "clawhark_channel"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_RATE = 16000
        const val CHUNK_DURATION_MS = 15 * 60 * 1000L // 15 minutes
        const val VAD_THRESHOLD = 500
        const val VAD_SILENCE_TIMEOUT_MS = 3000L
        const val AAC_BIT_RATE = 32000 // 32kbps — good for voice
        const val READ_BUFFER_SAMPLES = 8192 // 512ms at 16kHz — halves CPU wakeups vs 4096
        const val STATUS_LOG_INTERVAL_MS = 300_000L // 5 min
        const val MIN_FREE_SPACE_BYTES = 50 * 1024 * 1024L // 50MB
        const val MAX_LOCAL_STORAGE_BYTES = 500 * 1024 * 1024L // Preserve queued audio; pause at the limit.
        const val RECOVERY_MAX_DELAY_MS = 60_000L
        const val STALE_TMP_THRESHOLD_MS = 20 * 60 * 1000L // Inspect old crash chunks before publication.

        // Shared preference keys (used by MainActivity too)
        const val PREF_FILE = "clawhark"
        const val PREF_SHOULD_RECORD = "should_record"
        const val ACTION_STOP = "STOP"
    }

    private val binder = LocalBinder()
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null
    private var statusJob: Job? = null

    enum class RecordingState { STOPPED, RECORDING, RECOVERING, STORAGE_FULL, PERMISSION_REQUIRED }

    @Volatile var recordingState = RecordingState.STOPPED
        private set

    // Stats
    @Volatile private var totalBytesEncoded = 0L
    @Volatile private var totalSilenceSkipped = 0L
    @Volatile private var totalReadErrors = 0
    @Volatile private var chunksWithVoice = 0
    @Volatile private var chunksWithoutVoice = 0

    @Volatile var recordingStartTime: Long = 0L
        private set
    @Volatile var totalChunks: Int = 0
        private set

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        AppLog.d(TAG, "onBind() called")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.d(TAG, "onUnbind() called")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(TAG, "=== SERVICE CREATED ===")
        AppLog.i(TAG, "Device: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        AppLog.i(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        AppLog.i(TAG, "Codec: AAC ${AAC_BIT_RATE/1000}kbps | Chunk: ${CHUNK_DURATION_MS/60000}min | Upload: every ${UploadScheduler.UPLOAD_INTERVAL_MINUTES}min")
        logBatteryStatus()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        AppLog.i(TAG, "onStartCommand() action=$action flags=$flags startId=$startId")

        when (action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "STOP requested — shutting down")
                getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHOULD_RECORD, false).apply()
                logStats()
                stopRecording()
                return START_NOT_STICKY
            }
            else -> {
                // On START_STICKY restart (null intent), check if user explicitly stopped
                if (intent == null) {
                    val shouldRecord = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                        .getBoolean(PREF_SHOULD_RECORD, true)
                    if (!shouldRecord) {
                        AppLog.i(TAG, "START_STICKY restart but user stopped — not restarting")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    AppLog.i(TAG, "START_STICKY restart — resuming recording")
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    isRecording = false
                    recordJob?.cancel()
                    recordingState = RecordingState.PERMISSION_REQUIRED
                    stopSelf()
                    ResumeRecordingNotification.show(this, "Grant microphone permission to resume recording")
                    return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION_ID, createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (error: SecurityException) {
                    AppLog.e(TAG, "Foreground microphone access unavailable", error)
                    isRecording = false
                    recordJob?.cancel()
                    recordingState = RecordingState.PERMISSION_REQUIRED
                    stopSelf()
                    ResumeRecordingNotification.show(this, "Open ClawHark to resume recording")
                    return START_NOT_STICKY
                }
                startRecording()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "=== SERVICE DESTROYED ===")
        logStats()
        isRecording = false
        scope.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        // The recording coroutine owns resource cleanup, including during cancellation.
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w(TAG, "onTaskRemoved() — app swiped from recents. Service should persist as foreground.")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "level=$level"
        }
        AppLog.w(TAG, "onTrimMemory($levelName)")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppLog.e(TAG, "onLowMemory() — system critically low!")
        super.onLowMemory()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Always-on audio recording"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        AppLog.d(TAG, "Notification channel created")
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val (title, message) = when (recordingState) {
            RecordingState.RECORDING -> "Recording" to "Listening..."
            RecordingState.RECOVERING -> "Recording interrupted" to "Waiting for the microphone; retrying automatically"
            RecordingState.STORAGE_FULL -> "Recording paused" to "Storage full; preserving audio and waiting for uploads"
            RecordingState.PERMISSION_REQUIRED -> "Microphone permission required" to "Open ClawHark to resume recording"
            RecordingState.STOPPED -> "ClawHark" to "Starting recording..."
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startRecording() {
        val previous = recordJob
        if (previous != null && !previous.isCompleted) {
            if (!previous.isActive) {
                scope.launch {
                    previous.join()
                    withContext(Dispatchers.Main) {
                        if (recordingRequested()) startRecording()
                    }
                }
            }
            return
        }

        ResumeRecordingNotification.cancel(this)
        UploadScheduler.schedule(this)
        recordJob = scope.launch(start = CoroutineStart.LAZY) {
            var failures = 0
            try {
                cleanupOrphanedTmpFiles()
                while (isActive && recordingRequested()) {
                    if (!hasEnoughDiskSpace()) {
                        updateRecordingState(RecordingState.STORAGE_FULL)
                        delay(RECOVERY_MAX_DELAY_MS)
                        continue
                    }
                    try {
                        openMicrophone()
                        acquireRecordingWakeLock()
                        recordingStartTime = System.currentTimeMillis()
                        updateRecordingState(RecordingState.RECORDING)
                        recordLoop()
                        failures = 0
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: SecurityException) {
                        AppLog.e(TAG, "Microphone permission unavailable", error)
                        updateRecordingState(RecordingState.PERMISSION_REQUIRED)
                        withContext(Dispatchers.Main) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            ResumeRecordingNotification.show(this@RecordingService, "Grant microphone permission to resume recording")
                        }
                        break
                    } catch (error: Exception) {
                        AppLog.e(TAG, "Recording interrupted; retrying", error)
                        failures++
                    } finally {
                        releaseAudioCapture()
                    }
                    if (isActive && recordingRequested()) {
                        if (!hasEnoughDiskSpace()) {
                            updateRecordingState(RecordingState.STORAGE_FULL)
                            delay(RECOVERY_MAX_DELAY_MS)
                        } else {
                            updateRecordingState(RecordingState.RECOVERING)
                            delay(minOf(5000L * (1L shl minOf(failures, 4)), RECOVERY_MAX_DELAY_MS))
                        }
                    }
                }
            } finally {
                isRecording = false
                releaseAudioCapture()
                withContext(NonCancellable) { UploadScheduler.uploadPending(this@RecordingService) }
            }
        }
        recordJob?.start()

        statusJob?.cancel()
        statusJob = scope.launch {
            while (isSessionActive()) {
                delay(STATUS_LOG_INTERVAL_MS)
                if (isSessionActive()) logPeriodicStatus()
            }
        }
    }

    private fun recordingRequested() = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
        .getBoolean(PREF_SHOULD_RECORD, true)

    private fun updateRecordingState(state: RecordingState) {
        recordingState = state
        isRecording = state == RecordingState.RECORDING
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    private fun openMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val readBufBytes = READ_BUFFER_SAMPLES * 2 // 2 bytes per 16-bit sample
        val internalBufSize = maxOf(minBufSize, readBufBytes) * 2
        AppLog.i(TAG, "AudioRecord minBufSize=$minBufSize, internal=$internalBufSize, readSamples=$READ_BUFFER_SAMPLES (${READ_BUFFER_SAMPLES * 1000 / SAMPLE_RATE}ms)")

        logAudioState()

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, internalBufSize
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be initialized" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            audioRecord = recorder
            AppLog.i(TAG, "Microphone capture started")
        } catch (error: Exception) {
            recorder.release()
            throw error
        }
    }

    private fun acquireRecordingWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawHark::Recording").apply {
            setReferenceCounted(false) // Prevents stacking — single release() fully releases
        }
        wakeLock?.acquire(CHUNK_DURATION_MS + 5 * 60 * 1000L)
        AppLog.i(TAG, "Wake lock acquired with a bounded chunk lease")
    }

    private fun releaseAudioCapture() {
        isRecording = false
        val recorder = audioRecord
        audioRecord = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        val lock = wakeLock
        wakeLock = null
        lock?.let { if (it.isHeld) it.release() }
    }

    private suspend fun recoverMicrophone(recorder: AudioRecord) {
        updateRecordingState(RecordingState.RECOVERING)
        logAudioState()
        try { recorder.stop() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}
        audioRecord = null
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) lock.release()
        var attempt = 0
        while (currentCoroutineContext().isActive && recordingRequested()) {
            val backoffMs = minOf(5000L * (1L shl minOf(attempt, 4)), RECOVERY_MAX_DELAY_MS)
            AppLog.i(TAG, "Mic recovery attempt ${attempt + 1} in ${backoffMs / 1000}s")
            delay(backoffMs)
            if (!recordingRequested()) return
            try {
                openMicrophone()
                acquireRecordingWakeLock()
                updateRecordingState(RecordingState.RECORDING)
                return
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                AppLog.w(TAG, "Microphone still unavailable; retrying")
                attempt++
            }
        }
    }

    private fun stopRecording() {
        AppLog.i(TAG, "=== STOPPING RECORDING ===")
        isRecording = false
        recordingState = RecordingState.STOPPED
        recordJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        // Cancel periodic uploads (no longer producing files) and trigger one final upload
        UploadScheduler.stopPeriodic(this)
        statusJob?.cancel()
        val previous = recordJob
        scope.launch {
            previous?.join()
            if (!recordingRequested()) {
                UploadScheduler.uploadPending(this@RecordingService)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    AppLog.flush()
                }
            }
        }
    }

    fun isCurrentlyRecording() = isRecording
    fun isSessionActive() = recordJob?.isActive == true

    // ─── Record Loop ─────────────────────────────────────────────────────

    private suspend fun recordLoop() {
        val buffer = ShortArray(READ_BUFFER_SAMPLES)
        var chunkStartTime = android.os.SystemClock.elapsedRealtime()
        var chunkWallTime = System.currentTimeMillis()
        var nextStorageCheck = 0L
        var lastVoiceTime = android.os.SystemClock.elapsedRealtime()
        var hasVoiceInChunk = false
        var chunkNumber = 0
        var readsSinceLastLog = 0
        var voiceReadsSinceLastLog = 0
        var silenceReadsSinceLastLog = 0
        var maxAmplSinceLastLog = 0

        val pcmByteBuffer = ByteArray(READ_BUFFER_SAMPLES * 2) // Pre-allocated — avoids GC in hot loop
        var encoder: AudioChunkEncoder? = null
        var pcmFed = 0L

        fun startNewChunk() {
            encoder?.release()
            encoder = null

            chunkStartTime = android.os.SystemClock.elapsedRealtime()
            chunkWallTime = System.currentTimeMillis()
            hasVoiceInChunk = false
            pcmFed = 0L
            chunkNumber++
            totalChunks++

            wakeLock?.acquire(CHUNK_DURATION_MS + 5 * 60 * 1000L)

            AppLog.i(TAG, "New chunk #$chunkNumber")
        }

        try {
            startNewChunk()

            while (currentCoroutineContext().isActive && recordingRequested()) {
                currentCoroutineContext().ensureActive()
                val storageNow = android.os.SystemClock.elapsedRealtime()
                if (storageNow >= nextStorageCheck) {
                    nextStorageCheck = storageNow + 5000L
                    if (!hasEnoughDiskSpace()) {
                        updateRecordingState(RecordingState.STORAGE_FULL)
                        break
                    }
                }
                val ar = audioRecord ?: break
                val read = ar.read(buffer, 0, buffer.size)

                if (read < 0) {
                    totalReadErrors++
                    val errorName = when (read) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "ERROR($read)"
                    }
                    AppLog.e(TAG, "AudioRecord.read() returned $errorName — totalReadErrors=$totalReadErrors")
                    recoverMicrophone(ar)
                    continue
                }

                if (read == 0) { delay(10); continue }

                readsSinceLastLog++

                var maxAmplitude = 0
                for (i in 0 until read) {
                    val abs = kotlin.math.abs(buffer[i].toInt())
                    if (abs > maxAmplitude) maxAmplitude = abs
                }
                if (maxAmplitude > maxAmplSinceLastLog) maxAmplSinceLastLog = maxAmplitude
                val now = android.os.SystemClock.elapsedRealtime()

                if (maxAmplitude > VAD_THRESHOLD) {
                    lastVoiceTime = now
                    hasVoiceInChunk = true
                    voiceReadsSinceLastLog++
                } else {
                    silenceReadsSinceLastLog++
                }

                val silenceDuration = now - lastVoiceTime
                if (hasVoiceInChunk && silenceDuration < VAD_SILENCE_TIMEOUT_MS) {
                    val pcmBytes = read * 2
                    for (i in 0 until read) {
                        pcmByteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        pcmByteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }

                    if (encoder == null && hasEnoughDiskSpace()) {
                        try {
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date(chunkWallTime))
                            val aacFile = File(getChunkDir(), "chunk_${timestamp}_${UUID.randomUUID()}.m4a")
                            encoder = AudioChunkEncoder(aacFile, SAMPLE_RATE, AAC_BIT_RATE)
                            AppLog.d(TAG, "Encoder created for chunk #$chunkNumber: ${aacFile.name}")
                        } catch (e: Exception) {
                            throw IllegalStateException("Cannot create AAC encoder", e)
                        }
                    }

                    encoder?.let { enc ->
                        try {
                            enc.feed(pcmByteBuffer, pcmBytes)
                            pcmFed += pcmBytes
                            totalBytesEncoded += pcmBytes
                        } catch (e: Exception) {
                            throw IllegalStateException("Cannot feed AAC encoder", e)
                        }
                    }
                } else {
                    totalSilenceSkipped += read * 2
                }

                val readsPerInterval = (30 * SAMPLE_RATE) / READ_BUFFER_SAMPLES
                if (readsSinceLastLog >= readsPerInterval) {
                    AppLog.d(TAG, "chunk#$chunkNumber: ${pcmFed/1024}KB PCM encoded, reads=$readsSinceLastLog voice=$voiceReadsSinceLastLog silence=$silenceReadsSinceLastLog maxAmpl=$maxAmplSinceLastLog")
                    readsSinceLastLog = 0; voiceReadsSinceLastLog = 0; silenceReadsSinceLastLog = 0; maxAmplSinceLastLog = 0
                }

                if (now - chunkStartTime >= CHUNK_DURATION_MS) {
                    AppLog.i(TAG, "Chunk #$chunkNumber done (${(now - chunkStartTime)/1000}s). hasVoice=$hasVoiceInChunk pcmFed=${pcmFed/1024}KB")

                    val enc = encoder
                    if (enc != null) {
                        chunksWithVoice++
                        encoder = null
                        val encoded = enc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "Chunk finalized: ${encoded.name} (${encoded.length()/1024}KB)")
                        } else {
                            throw IllegalStateException("Chunk finalization failed; temporary audio preserved")
                        }
                    } else {
                        chunksWithoutVoice++
                        AppLog.d(TAG, "Chunk #$chunkNumber silent — no encoder created ($chunksWithoutVoice silent total)")
                    }

                    startNewChunk()
                }
            }
        } finally {
            // Finalize the current chunk even if capture failed or the job was cancelled.
            isRecording = false
            if (recordingState == RecordingState.RECORDING) {
                updateRecordingState(if (recordingRequested()) RecordingState.RECOVERING else RecordingState.STOPPED)
            }
            withContext(NonCancellable) {
                AppLog.i(TAG, "recordLoop: finalizing and cleaning up")

                // Finalize last encoder chunk
                val finalEnc = encoder
                if (finalEnc != null) {
                    encoder = null
                    try {
                        val encoded = finalEnc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "Final chunk: ${encoded.name} (${encoded.length()/1024}KB)")
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Final chunk finalization failed", e)
                        finalEnc.release()
                    }
                } else {
                    AppLog.d(TAG, "Final chunk had no voice — no encoder to finalize")
                }

                releaseAudioCapture()

                AppLog.d(TAG, "recordLoop: cleanup complete")
            }
        }
    }

    // ─── Status & Logging ────────────────────────────────────────────────

    private fun logPeriodicStatus() {
        val uptimeMin = (System.currentTimeMillis() - recordingStartTime) / 60000
        val localFiles = getRecordings().size
        val localMB = String.format("%.1f", getStorageUsed() / 1024.0 / 1024.0)
        AppLog.i(TAG, "=== STATUS (${uptimeMin}m uptime) ===")
        AppLog.i(TAG, "  Recording: $isRecording | AudioRecord state: ${audioRecord?.state}")
        AppLog.i(TAG, "  Chunks: $totalChunks total ($chunksWithVoice voice, $chunksWithoutVoice silent)")
        AppLog.i(TAG, "  PCM encoded: ${totalBytesEncoded/1024/1024}MB | Silence skipped: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  Local files: $localFiles ($localMB MB) — uploads every ${UploadScheduler.UPLOAD_INTERVAL_MINUTES}min")
        AppLog.i(TAG, "  Read errors: $totalReadErrors")
        AppLog.i(TAG, "  WakeLock held: ${wakeLock?.isHeld}")
        AppLog.i(TAG, "  Free space: ${getChunkDir().usableSpace / 1024 / 1024}MB")
        logBatteryStatus(); logAudioState()
    }

    private fun logStats() {
        val uptimeSec = if (recordingStartTime > 0) (System.currentTimeMillis() - recordingStartTime) / 1000 else 0
        AppLog.i(TAG, "=== FINAL STATS (${uptimeSec}s uptime) ===")
        AppLog.i(TAG, "  Chunks: $totalChunks ($chunksWithVoice voice, $chunksWithoutVoice silent)")
        AppLog.i(TAG, "  PCM encoded: ${totalBytesEncoded/1024/1024}MB | Silence skipped: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  Read errors: $totalReadErrors")
    }

    private fun logBatteryStatus() {
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            AppLog.d(TAG, "Battery: ${level}% charging=${bm.isCharging}")
        } catch (_: Exception) { AppLog.d(TAG, "Battery: unable to read") }
    }

    private fun logAudioState() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = when (am.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"; AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"; AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"; else -> "mode=${am.mode}"
            }
            AppLog.d(TAG, "Audio: mode=$mode micMuted=${am.isMicrophoneMute} musicActive=${am.isMusicActive}")
        } catch (_: Exception) { AppLog.d(TAG, "Audio: unable to read") }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────

    private fun cleanupOrphanedTmpFiles() {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) return
        val now = System.currentTimeMillis()
        val tmpFiles = dir.listFiles()?.filter { it.extension == "tmp" } ?: emptyList()
        for (tmp in tmpFiles) {
            val ageMs = now - tmp.lastModified()
            if (ageMs > STALE_TMP_THRESHOLD_MS && tmp.length() > 0) {
                // MP4 headers may never have been finalized. Only publish readable audio.
                val extractor = MediaExtractor()
                val valid = try {
                    extractor.setDataSource(tmp.absolutePath)
                    (0 until extractor.trackCount).any {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    }
                } catch (_: Exception) { false } finally { extractor.release() }
                if (!valid) {
                    AppLog.w(TAG, "Keeping incomplete crash recording ${tmp.name}; it is not uploadable")
                    continue
                }
                val m4aName = tmp.name.removeSuffix(".tmp")
                val recovered = File(dir, m4aName)
                if (!recovered.exists() && tmp.renameTo(recovered)) {
                    AppLog.i(TAG, "Recovered orphaned .tmp → ${recovered.name} (${tmp.length()/1024}KB, age ${ageMs/1000}s)")
                } else {
                    AppLog.w(TAG, "Failed to recover ${tmp.name} — preserving")
                }
            } else if (ageMs > STALE_TMP_THRESHOLD_MS) {
                // Old but empty — just delete
                AppLog.d(TAG, "Deleting empty orphaned .tmp: ${tmp.name}")
                tmp.delete()
            } else {
                // Recent .tmp — may still be actively encoding (shouldn't happen on startup, but be safe)
                AppLog.d(TAG, "Skipping recent .tmp: ${tmp.name} (age ${ageMs/1000}s)")
            }
        }
    }

    // ─── Storage ─────────────────────────────────────────────────────────

    private fun hasEnoughDiskSpace(): Boolean {
        if (getStorageUsed() >= MAX_LOCAL_STORAGE_BYTES) {
            AppLog.w(TAG, "Pending audio reached storage limit — preserving recordings and pausing capture")
            return false
        }
        val freeSpace = getChunkDir().usableSpace
        if (freeSpace < MIN_FREE_SPACE_BYTES) {
            AppLog.w(TAG, "Low disk space: ${freeSpace / 1024 / 1024}MB free (min ${MIN_FREE_SPACE_BYTES / 1024 / 1024}MB) — skipping encoding")
            return false
        }
        return true
    }

    fun getChunkDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getRecordings(): List<File> {
        return getChunkDir().listFiles()?.filter { it.extension == "m4a" }?.sortedBy { it.name } ?: emptyList()
    }

    fun getStorageUsed(): Long = getChunkDir().listFiles()?.filter {
        it.name.endsWith(".m4a") || it.name.endsWith(".m4a.tmp") || it.name.endsWith(".m4a.uploading")
    }?.sumOf { it.length() } ?: 0L
}
