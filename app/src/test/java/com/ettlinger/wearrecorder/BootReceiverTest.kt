package com.ettlinger.wearrecorder

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Exercise the receiver with native SharedPreferences without requiring a hardware keystore.
        val prefs = application.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        prefs.edit().putString("refresh_token", "test-only-token").commit()
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, prefs)
        setRecordingRequested(true)
    }

    @After
    fun tearDown() {
        AuthManager::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(AuthManager, null)
    }

    @Test
    fun rebootAttemptsAutomaticRecording() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RecordingService::class.java.name, shadowOf(application).nextStartedService.component?.className)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    @Config(sdk = [30])
    fun automaticRestartWorksOnOldestSupportedAndroid() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RecordingService::class.java.name, shadowOf(application).nextStartedService.component?.className)
    }

    @Test
    fun rejectedMicrophonePermissionProducesResumePrompt() {
        BootReceiver().onReceive(rejectingContext(SecurityException("Background microphone restricted")),
            Intent(Intent.ACTION_BOOT_COMPLETED))
        assertResumePrompt()
    }

    @Test
    fun rejectedForegroundStartProducesResumePrompt() {
        BootReceiver().onReceive(rejectingContext(ForegroundServiceStartNotAllowedException("Background start restricted")),
            Intent(Intent.ACTION_BOOT_COMPLETED))
        assertResumePrompt()
    }

    private fun assertResumePrompt() {
        assertNull(shadowOf(application).nextStartedService)
        val notifications = application.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, notifications.size)
        assertNotNull(notifications.single().notification.contentIntent)
        notifications.single().notification.contentIntent.send()
        assertEquals(MainActivity::class.java.name, shadowOf(application).nextStartedActivity.component?.className)
    }

    @Test
    fun explicitStopDoesNotProduceResumePrompt() {
        setRecordingRequested(false)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun unrelatedBroadcastDoesNothing() {
        BootReceiver().onReceive(application, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun signedOutDoesNotRestartRecording() {
        application.getSharedPreferences("test_auth", Context.MODE_PRIVATE).edit().clear().commit()
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun missingNotificationPermissionDoesNotBlockAutomaticRestart() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BootReceiver().onReceive(application, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RecordingService::class.java.name, shadowOf(application).nextStartedService.component?.className)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test
    fun rejectedStartWithoutNotificationPermissionDoesNotCrash() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BootReceiver().onReceive(rejectingContext(SecurityException("Background microphone restricted")),
            Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(application).nextStartedService)
        assertEquals(0, application.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    private fun rejectingContext(error: RuntimeException): Context = object : ContextWrapper(application) {
        override fun startForegroundService(service: Intent): ComponentName? = throw error
    }

    private fun setRecordingRequested(enabled: Boolean) {
        application.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, enabled).commit()
    }
}
