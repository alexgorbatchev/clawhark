package com.ettlinger.wearrecorder

import android.Manifest
import android.app.Service
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingServiceTest {
    private lateinit var service: RecordingService

    @Before
    fun setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        service = Robolectric.buildService(RecordingService::class.java).create().get()
        setRecordingRequested(true)
        setField("isRecording", true)
    }

    @After
    fun tearDown() {
        setRecordingRequested(false)
        service.onDestroy()
        service.getChunkDir().deleteRecursively()
    }

    @Test
    fun missingMicrophoneClearsRecordingState() = runTest {
        invokeRecordLoop()
        assertFalse("A missing microphone must not be reported as recording", service.isCurrentlyRecording())
    }

    @Test
    fun readExceptionClearsStateAndReleasesMicrophone() = runTest {
        val recorder = newRecorder()
        setField("audioRecord", recorder)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    throw IllegalStateException("Microphone read failed")
                }
            }
        }
        try {
            invokeRecordLoop()
            fail("Expected microphone read failure")
        } catch (_: IllegalStateException) {
            assertFalse(service.isCurrentlyRecording())
            assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        }
    }

    @Test
    fun queuedRecordingsSurviveStoragePressure() = runTest {
        val queued = oversizedRecording()
        invokeRecordLoop()
        assertTrue("Unuploaded audio must never be evicted", queued.exists())
        assertEquals(RecordingService.MAX_LOCAL_STORAGE_BYTES + 1, queued.length())
    }

    @Test
    fun fullBacklogPreventsNewEncoding() {
        val queued = oversizedRecording()
        val method = RecordingService::class.java.getDeclaredMethod("hasEnoughDiskSpace")
        method.isAccessible = true
        assertFalse("Pause encoding instead of consuming space reserved for pending uploads", method.invoke(service) as Boolean)
        assertTrue(queued.exists())
    }

    @Test
    fun inFlightUploadCountsAgainstStorageLimit() {
        val queued = oversizedRecording()
        val uploading = File(queued.parent, queued.name + ".uploading")
        assertTrue(queued.renameTo(uploading))
        assertEquals(RecordingService.MAX_LOCAL_STORAGE_BYTES + 1, service.getStorageUsed())
    }

    @Test
    fun activeChunkCountsAgainstStorageLimit() {
        val queued = oversizedRecording()
        val temporary = File(queued.parent, queued.name + ".tmp")
        assertTrue(queued.renameTo(temporary))
        assertEquals(RecordingService.MAX_LOCAL_STORAGE_BYTES + 1, service.getStorageUsed())
    }

    @Test
    fun revokedMicrophonePermissionDoesNotStartStickyService() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(RecordingService.RecordingState.PERMISSION_REQUIRED, service.recordingState)
    }

    @Test
    fun readErrorDoesNotReportActiveRecordingDuringRecovery() = runTest {
        setField("audioRecord", newRecorder())
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean) = AudioRecord.ERROR_INVALID_OPERATION
            }
        }
        val job = launch { invokeRecordLoop() }
        runCurrent()
        assertFalse("A failed audio read must show recovery, not recording", service.isCurrentlyRecording())
        job.cancel()
        job.join()
    }

    @Test
    fun invalidOperationRecreatesMicrophone() = runTest {
        val first = newRecorder()
        setField("audioRecord", first)
        var recovered = false
        // Keep the user's intent enabled through the first failure; stop after the next read.
        var reads = 0
        ShadowAudioRecord.setSourceProvider { recorder ->
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    reads++
                    if (reads == 1) return AudioRecord.ERROR_INVALID_OPERATION
                    recovered = recorder !== first
                    setRecordingRequested(false)
                    setField("isRecording", false)
                    return 0
                }
            }
        }
        invokeRecordLoop()
        assertTrue("Invalid-operation errors require a new AudioRecord", recovered)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, first.state)
    }

    @Test
    fun fullStoragePausesAndResumesAfterQueuedAudioIsUploaded() = runTest {
        val queued = oversizedRecording()
        prepareSession(backgroundScope)
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean) = 0
            }
        }
        service.onStartCommand(null, 0, 1)
        runCurrent()
        assertEquals(RecordingService.RecordingState.STORAGE_FULL, service.recordingState)
        assertFalse(service.isCurrentlyRecording())
        assertTrue(service.isSessionActive())
        assertTrue(queued.exists())

        // UploadWorker removes a completed file only after Drive acknowledges success.
        assertTrue(queued.delete())
        advanceTimeBy(RecordingService.RECOVERY_MAX_DELAY_MS)
        runCurrent()
        assertTrue(service.isCurrentlyRecording())
        assertEquals(RecordingService.RecordingState.RECORDING, service.recordingState)
    }

    @Test
    fun captureCrashRetriesWithoutLeavingStaleRecordingState() = runTest {
        prepareSession(backgroundScope)
        var reads = 0
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    if (++reads == 1) throw IllegalStateException("Transient audio driver failure")
                    return 0
                }
            }
        }
        service.onStartCommand(null, 0, 1)
        runCurrent()
        assertFalse(service.isCurrentlyRecording())
        assertEquals(RecordingService.RecordingState.RECOVERING, service.recordingState)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(service.isCurrentlyRecording())
        assertTrue(reads > 1)
    }

    @Test
    fun explicitStopCancelsMicrophoneRecovery() = runTest {
        prepareSession(backgroundScope)
        var reads = 0
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(data: ShortArray, offset: Int, size: Int, blocking: Boolean): Int {
                    reads++
                    return AudioRecord.ERROR_DEAD_OBJECT
                }
            }
        }
        service.onStartCommand(null, 0, 1)
        runCurrent()
        assertEquals(RecordingService.RecordingState.RECOVERING, service.recordingState)
        service.onStartCommand(android.content.Intent(service, RecordingService::class.java).setAction(RecordingService.ACTION_STOP), 0, 2)
        runCurrent()
        val readsAtStop = reads
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(service.isSessionActive())
        assertFalse(service.isCurrentlyRecording())
        assertEquals(readsAtStop, reads)
        assertFalse(service.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(RecordingService.PREF_SHOULD_RECORD, true))
    }

    private fun prepareSession(scope: CoroutineScope) {
        setField("scope", scope)
        WorkManagerTestInitHelper.initializeTestWorkManager(service, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).build())
    }

    private fun oversizedRecording(): File = File(service.getChunkDir(), "chunk_pending.m4a").also {
        RandomAccessFile(it, "rw").use { file -> file.setLength(RecordingService.MAX_LOCAL_STORAGE_BYTES + 1) }
    }

    private fun newRecorder() = AudioRecord(
        MediaRecorder.AudioSource.MIC, RecordingService.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 32768
    ).also { it.startRecording() }

    private fun setRecordingRequested(enabled: Boolean) {
        service.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, enabled).commit()
    }

    private fun setField(name: String, value: Any) {
        RecordingService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private suspend fun invokeRecordLoop(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = RecordingService::class.java.getDeclaredMethod("recordLoop", Continuation::class.java)
        method.isAccessible = true
        try {
            method.invoke(service, continuation)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
