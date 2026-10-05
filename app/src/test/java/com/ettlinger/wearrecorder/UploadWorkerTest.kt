package com.ettlinger.wearrecorder

import android.content.Context
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadWorkerTest {
    private lateinit var directory: File
    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, prefs)
        directory = File(app.filesDir, "recordings").apply { mkdirs() }
    }
    @After fun tearDown() {
        directory.deleteRecursively()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, null)
    }
    @Test fun recentOrphanedClaimIsRecoveredImmediately() = runTest {
        val claim = File(directory, "chunk.m4a.uploading").apply { writeText("audio") }
        TestListenableWorkerBuilder<UploadWorker>(RuntimeEnvironment.getApplication()).build().doWork()
        assertFalse(claim.exists())
        assertEquals("audio", File(directory, "chunk.m4a").readText())
    }
    @Test fun activeUploadIsNeverStolenBasedOnRecordingAge() = runTest {
        val claim = File(directory, "chunk.m4a.uploading").apply {
            writeText("audio")
            setLastModified(System.currentTimeMillis() - 3_600_000)
        }
        RandomAccessFile(File(directory, ".upload.lock"), "rw").use { handle ->
            handle.channel.lock().use {
                TestListenableWorkerBuilder<UploadWorker>(RuntimeEnvironment.getApplication()).build().doWork()
                assertTrue(claim.exists())
                assertFalse(File(directory, "chunk.m4a").exists())
            }
        }
    }
}
